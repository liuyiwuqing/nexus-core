package dev.nexus.core.security;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserSessionStoreTest {
    @TempDir Path directory;
    final Instant now=Instant.parse("2026-11-17T00:00:00Z");
    BrowserSessions.Session entry(String key,Instant expires){return new BrowserSessions.Session(key,"b".repeat(64),"c".repeat(43),expires);}
    @Test void aSecondStoreReadsPrivateMetadataAndSeesCommittedLogout()throws Exception{
        var first=new BrowserSessionStore(directory.toString());var session=entry("a".repeat(64),now.plusSeconds(28800));first.replace(null,session,now);
        Path file=directory.resolve(session.idHash()+".json");assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var second=new BrowserSessionStore(directory.toString());assertThat(second.read(session.idHash())).isEqualTo(session);
        second.remove(session.idHash());assertThat(first.read(session.idHash())).isNull();assertThat(new BrowserSessionStore(directory.toString()).read(session.idHash())).isNull();
    }
    @Test void malformedOrUnsafeStateIsRejectedWithoutGuessingOrRepair()throws Exception{
        var store=new BrowserSessionStore(directory.toString());var entry=entry("a".repeat(64),now.plusSeconds(28800));store.replace(null,entry,now);Path file=directory.resolve(entry.idHash()+".json");byte[] original=Files.readAllBytes(file);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThatThrownBy(()->store.read(entry.idHash())).isInstanceOf(BrowserSessionStore.Unavailable.class);assertThat(Files.readAllBytes(file)).isEqualTo(original);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Files.writeString(file,"{\"format\":1,\"format\":1}");assertThatThrownBy(()->store.read(entry.idHash())).isInstanceOf(BrowserSessionStore.Unavailable.class);
        Files.delete(file);Path other=Files.write(directory.resolve("other.json"),original);Files.createSymbolicLink(file,other);assertThatThrownBy(()->store.read(entry.idHash())).isInstanceOf(BrowserSessionStore.Unavailable.class);assertThatThrownBy(()->store.remove(entry.idHash())).isInstanceOf(BrowserSessionStore.Unavailable.class);assertThat(Files.readAllBytes(other)).isEqualTo(original);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-xr-x"));assertThatThrownBy(()->store.read(entry.idHash())).isInstanceOf(BrowserSessionStore.Unavailable.class);
    }
    @Test void capacityIsBoundedAndExpiredStateIsReclaimedOnlyWhenCreatingASession()throws Exception{
        var store=new BrowserSessionStore(directory.toString());var first=entry("a".repeat(64),now.plusSeconds(28800));store.replace(null,first,now);byte[] raw=Files.readAllBytes(directory.resolve(first.idHash()+".json"));
        for(int i=0;i<1023;i++){var path=Files.createFile(directory.resolve(String.format("%064x",i)+".json"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.write(path,raw);}
        var next=entry("d".repeat(64),now.plusSeconds(30000));assertThatThrownBy(()->store.replace(null,next,now)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        store.replace(null,next,now.plusSeconds(28800));assertThat(store.read(first.idHash())).isNull();assertThat(store.read(next.idHash())).isEqualTo(next);
        try(var files=Files.list(directory)){assertThat(files.filter(p->p.getFileName().toString().endsWith(".json")).count()).isEqualTo(1);}
    }
}
