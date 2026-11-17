package dev.nexus.core.security;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A protected local file is reread for each authorization; an invalid update never falls back to old secrets. */
@Component
public final class TokenCredentials {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(256).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final class Unavailable extends RuntimeException {
        Unavailable(){super("authentication_configuration_unavailable");}
    }
    private record Credential(String id,byte[] bearer,long expiresAt) {}
    private record Snapshot(long revision,String actor,List<Credential> admin,List<Credential> memory) {}
    private final Path file;
    private final String legacyAdmin,legacyMemory;
    private final boolean memoryEnabled;
    private String actor;
    private long revision;
    private byte[] fingerprint;

    public TokenCredentials(@Value("${nexus.auth.token-file:}") String filename,
        @Value("${nexus.admin-token:}") String admin,@Value("${nexus.memory.mcp.token:}") String memory,
        @Value("${nexus.memory.mcp.enabled:false}") boolean memoryEnabled) {
        this.memoryEnabled=memoryEnabled;legacyAdmin=admin;legacyMemory=memory;
        if(filename.isBlank()) {
            file=null;
            if(admin.length()<32||admin.chars().anyMatch(Character::isWhitespace))
                throw new IllegalStateException("NEXUS_ADMIN_TOKEN must contain at least 32 non-whitespace characters");
            if(memoryEnabled&&(!validToken(memory)||memory.equals(admin)))
                throw new IllegalStateException("NEXUS_MEMORY_MCP_TOKEN must be a separate 32-256 character machine credential");
            actor=digest(admin.getBytes(StandardCharsets.UTF_8));
        }else {
            file=Path.of(filename).normalize();
            try {if(!file.isAbsolute())throw new Unavailable();read();}
            catch(RuntimeException ignored){throw new IllegalStateException("NEXUS_AUTH_TOKEN_FILE is unavailable or invalid");}
        }
    }
    public String adminActor(){return actor;}
    public boolean acceptsAdmin(String supplied) {
        return file==null?matches(legacyAdmin,supplied):accepts(read().admin(),supplied);
    }
    public boolean acceptsMemory(String supplied) {
        return memoryEnabled&&(file==null?matches(legacyMemory,supplied):accepts(read().memory(),supplied));
    }
    private static boolean matches(String token,String supplied) {
        return supplied!=null&&MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean accepts(List<Credential> values,String supplied) {
        if(supplied==null)return false;
        byte[] bytes=supplied.getBytes(StandardCharsets.UTF_8);long now=Instant.now().getEpochSecond();boolean accepted=false;
        for(var value:values)accepted|=MessageDigest.isEqual(value.bearer(),bytes)&value.expiresAt()>now;
        return accepted;
    }
    public synchronized Map<String,Object> management(){
        if(file==null)return Map.of("mode","environment","editable",false);
        var snapshot=read();
        try{return Map.of("mode","file","revision",snapshot.revision(),"editable",Files.isWritable(file)&&Files.isWritable(file.getParent())&&Files.getPosixFilePermissions(file).contains(PosixFilePermission.OWNER_WRITE));}
        catch(java.io.IOException unavailable){throw new Unavailable();}
    }
    /** Same-directory atomic replacement, stable actor and memory scope, no overlap. */
    public synchronized long changeAdmin(long expected,String current,String next){
        if(file==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"admin_token_environment_managed");
        if(current==null||next==null||!validToken(next))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_admin_token");
        Path lock=file.resolveSibling(file.getFileName()+".lock"),temporary=null;
        try {
            try{Files.createFile(lock,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException exists){/* Validate the existing private lock below. */}
            if(!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(lock,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))throw new Unavailable();
            try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var guard=channel.tryLock()){
                if(guard==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"credential_store_busy");
                var snapshot=read();
                if(snapshot.revision()!=expected||expected==Long.MAX_VALUE)throw new ResponseStatusException(HttpStatus.CONFLICT,"admin_token_version_conflict");
                if(!Files.getPosixFilePermissions(file).contains(PosixFilePermission.OWNER_WRITE)||!Files.isWritable(file)||!Files.isWritable(file.getParent()))throw new ResponseStatusException(HttpStatus.CONFLICT,"admin_token_readonly");
                byte[] proof=("Bearer "+current).getBytes(StandardCharsets.UTF_8);
                if(!MessageDigest.isEqual(snapshot.admin().getFirst().bearer(),proof))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_current_admin_token");
                String authorization="Bearer "+next;
                if(java.util.stream.Stream.concat(snapshot.admin().stream(),snapshot.memory().stream()).anyMatch(c->MessageDigest.isEqual(c.bearer(),authorization.getBytes(StandardCharsets.UTF_8))))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"admin_token_reused");
                var value=(ObjectNode)JSON.readTree(Files.readAllBytes(file));
                // Preserve the complete independent memory scope, including overlap.
                value.set("admin",JSON.valueToTree(Map.of("current",Map.of("id",UUID.randomUUID().toString().replace("-",""),"token",next))));value.put("revision",expected+1);
                byte[] bytes=JSON.writeValueAsBytes(value);
                temporary=Files.createTempFile(file.getParent(),"."+file.getFileName()+"-","",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                var attributes=Files.readAttributes(file,java.nio.file.attribute.PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                Files.setOwner(temporary,attributes.owner());Files.getFileAttributeView(temporary,java.nio.file.attribute.PosixFileAttributeView.class).setGroup(attributes.group());
                try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                if(read().revision()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"admin_token_version_conflict");
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temporary=null;
                try(var directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
                return read().revision();
            }
        }catch(ResponseStatusException rejected){throw rejected;}
        catch(OverlappingFileLockException busy){throw new ResponseStatusException(HttpStatus.CONFLICT,"credential_store_busy");}
        catch(Exception unavailable){throw new Unavailable();}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){/* No secret enters logs. */}}
    }
    private static String digest(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception impossible){throw new IllegalStateException("sha256_unavailable");}
    }
    private static void fields(JsonNode value,Set<String> required,Set<String> optional) {
        if(value==null||!value.isObject()||!value.propertyNames().containsAll(required))throw new Unavailable();
        for(String key:value.propertyNames())if(!required.contains(key)&&!optional.contains(key))throw new Unavailable();
    }
    private static long integer(JsonNode value,long minimum,long maximum) {
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<minimum||value.longValue()>maximum)throw new Unavailable();
        return value.longValue();
    }
    private static String text(JsonNode value) {
        if(value==null||!value.isString())throw new Unavailable();return value.asText();
    }
    private static boolean validToken(String value) {
        return value.length()>=32&&value.length()<=256&&value.chars().noneMatch(c->c<33||c>126);
    }
    private static List<Credential> scope(JsonNode value,Set<String> ids,Set<String> tokens) {
        fields(value,Set.of("current"),Set.of("previous"));var out=new ArrayList<Credential>();
        for(String slot:List.of("current","previous")) {
            if(!value.has(slot))continue;var entry=value.get(slot);
            fields(entry,slot.equals("current")?Set.of("id","token"):Set.of("id","token","expiresAt"),Set.of());
            String id=text(entry.get("id")),token=text(entry.get("token"));
            if(!id.matches("[a-zA-Z0-9_-]{1,40}")||!ids.add(id)||!validToken(token)||!tokens.add(token))throw new Unavailable();
            long expiry=slot.equals("current")?Long.MAX_VALUE:integer(entry.get("expiresAt"),1,Instant.now().getEpochSecond()+86400);
            out.add(new Credential(id,("Bearer "+token).getBytes(StandardCharsets.UTF_8),expiry));
        }
        return List.copyOf(out);
    }
    private synchronized Snapshot read() {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new Unavailable();
            var permissions=Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS);
            var permitted=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
            if(!permissions.contains(PosixFilePermission.OWNER_READ)||!permitted.containsAll(permissions))throw new Unavailable();
            var parent=Files.getPosixFilePermissions(file.getParent());
            if(parent.contains(PosixFilePermission.GROUP_WRITE)||parent.contains(PosixFilePermission.OTHERS_WRITE))throw new Unavailable();
            byte[] bytes;
            try(InputStream stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(16385);}
            if(bytes.length==0||bytes.length>16384)throw new Unavailable();
            var value=JSON.readTree(bytes);fields(value,Set.of("format","revision","adminActor","admin"),Set.of("memory"));
            integer(value.get("format"),1,1);long next=integer(value.get("revision"),1,Long.MAX_VALUE);
            String principal=text(value.get("adminActor"));
            if(!principal.matches("[0-9a-f]{64}")||(actor!=null&&!actor.equals(principal)))throw new Unavailable();
            var ids=new HashSet<String>();var tokens=new HashSet<String>();
            var admin=scope(value.get("admin"),ids,tokens);
            var memory=value.has("memory")?scope(value.get("memory"),ids,tokens):List.<Credential>of();
            if(memoryEnabled&&memory.isEmpty())throw new Unavailable();
            byte[] hash=HexFormat.of().parseHex(digest(bytes));
            if(next<revision||(next==revision&&!MessageDigest.isEqual(fingerprint,hash)))throw new Unavailable();
            actor=principal;revision=next;fingerprint=hash;
            return new Snapshot(next,principal,admin,memory);
        }catch(Exception ignored){throw new Unavailable();}
    }
}
