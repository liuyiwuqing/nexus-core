package dev.nexus.core.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private durable sessions. Filenames hash Cookie IDs; values contain no Bearer credential. */
@Component
public final class BrowserSessionStore {
    private static final int MAXIMUM=1024;
    private static final Set<PosixFilePermission> PRIVATE_FILE=PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY=PosixFilePermissions.fromString("rwx------");
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(128).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final class Unavailable extends RuntimeException {
        Unavailable(){super("session_store_unavailable");}
    }
    private final Path directory;
    public BrowserSessionStore(@Value("${nexus.auth.session-directory:data/auth/sessions}") String directory){this.directory=Path.of(directory).toAbsolutePath().normalize();}
    private static boolean key(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private Path file(String key){if(!key(key))throw new Unavailable();return directory.resolve(key+".json");}
    private void directory()throws IOException {
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(directory,PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(directory,LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_DIRECTORY))throw new Unavailable();
    }
    private void privateFile(Path path)throws IOException {
        var attributes=Files.readAttributes(path,java.nio.file.attribute.PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()||!attributes.permissions().equals(PRIVATE_FILE))throw new Unavailable();
    }
    private BrowserSessions.Session readFile(String key)throws IOException {
        Path path=file(key);
        try {
            privateFile(path);
            byte[] bytes;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(1025);}
            if(bytes.length==0||bytes.length>1024)throw new Unavailable();
            var value=JSON.readTree(bytes);
            if(!value.isObject()||!value.propertyNames().equals(Set.of("format","credentialFingerprint","csrfToken","expiresAt"))||!value.path("format").isIntegralNumber()||!value.path("format").canConvertToInt()||value.path("format").asInt()!=1)throw new Unavailable();
            if(!value.path("credentialFingerprint").isString()||!key(value.path("credentialFingerprint").asText())||!value.path("csrfToken").isString()||!value.path("csrfToken").asText().matches("[A-Za-z0-9_-]{43}")||!value.path("expiresAt").isString())throw new Unavailable();
            return new BrowserSessions.Session(key,value.path("credentialFingerprint").asText(),value.path("csrfToken").asText(),Instant.parse(value.path("expiresAt").asText()));
        }catch(NoSuchFileException missing){return null;}
    }
    public BrowserSessions.Session read(String key){try{directory();return readFile(key);}catch(Exception unavailable){throw new Unavailable();}}
    private void sync()throws IOException{try(var parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}}
    public synchronized void replace(String previous,BrowserSessions.Session next,Instant now){
        Path temporary=null;
        try {
            directory();Path lock=directory.resolve(".lock");
            try{Files.createFile(lock,PosixFilePermissions.asFileAttribute(PRIVATE_FILE));}catch(FileAlreadyExistsException exists){/* All processes share this protected POSIX lock. */}
            privateFile(lock);
            try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var guard=channel.tryLock()){
                if(guard==null)throw new Unavailable();
                List<Path> files;try(var paths=Files.list(directory)){files=paths.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.json")).limit(MAXIMUM+1L).toList();}
                int count=0;
                for(var path:files){String key=path.getFileName().toString().substring(0,64);var session=readFile(key);if(session==null)continue;
                    if(!session.expiresAt().isAfter(now)){Files.deleteIfExists(path);continue;}
                    if(!key.equals(previous))count++;
                }
                if(count>=MAXIMUM)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"session_capacity_reached");
                byte[] bytes=JSON.writeValueAsBytes(Map.of("format",1,"credentialFingerprint",next.fingerprint(),"csrfToken",next.csrf(),"expiresAt",next.expiresAt().toString()));
                temporary=Files.createTempFile(directory,".session-",".tmp",PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
                try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                Files.move(temporary,file(next.idHash()),StandardCopyOption.ATOMIC_MOVE);temporary=null;
                if(previous!=null&&!previous.equals(next.idHash()))Files.deleteIfExists(file(previous));
                sync();
            }
        }catch(ResponseStatusException rejected){throw rejected;}
        catch(OverlappingFileLockException busy){throw new Unavailable();}
        catch(Exception unavailable){throw new Unavailable();}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){/* Never log private session content. */}}
    }
    public void remove(String key){
        if(key==null)return;
        try{directory();Path path=file(key);privateFile(path);Files.delete(path);sync();}
        catch(NoSuchFileException removed){/* Another logout already committed the same removal. */}
        catch(Exception unavailable){throw new Unavailable();}
    }
}
