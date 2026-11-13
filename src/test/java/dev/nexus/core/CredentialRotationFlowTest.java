package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP and protected files. Rotation must not require a service restart. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CredentialRotationFlowTest {
    static final String OLD="old-admin-credential-fixture-long-enough";
    static final String NEXT="next-admin-credential-fixture-long-enough";
    static final String MEMORY="memory-only-credential-fixture-long-enough";
    static final String NEXT_MEMORY="next-memory-credential-fixture-long-enough";
    static final String LEGACY="legacy-env-credential-fixture-long-enough";
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicLong REVISION=new AtomicLong();
    static final Path DIRECTORY=directory(),FILE=DIRECTORY.resolve("tokens.json");
    static final AtomicReference<CountDownLatch> STARTED=new AtomicReference<>(),RELEASE=new AtomicReference<>();
    static final HttpServer PROVIDER=provider();
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    static { try { replace(OLD,null,0); }catch(Exception e){throw new ExceptionInInitializerError(e);} }
    static Path directory() {
        try {return Files.createTempDirectory("nexus-token-flow-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static HttpServer provider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                try {
                    var started=STARTED.get();var release=RELEASE.get();if(started!=null)started.countDown();
                    if(release!=null)try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    byte[] body="{\"items\":[],\"total\":0,\"hasNext\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }finally{exchange.close();}
            });server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static Map<String,Object> credential(String id,String token){return Map.of("id",id,"token",token);}
    static void replace(String current,String previous,long expiry)throws Exception {
        var admin=new LinkedHashMap<String,Object>();admin.put("current",credential("admin-current",current));
        if(previous!=null)admin.put("previous",Map.of("id","admin-previous","token",previous,"expiresAt",expiry));
        var value=Map.of("format",1,"revision",REVISION.incrementAndGet(),"adminActor","0".repeat(64),
            "admin",admin,"memory",Map.of("current",credential("memory-current",MEMORY)));
        publish(value);
    }
    static void publish(Object value)throws Exception {
        var temporary=Files.createTempFile(DIRECTORY,"tokens-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(temporary,JSON.writeValueAsBytes(value));Files.move(temporary,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->LEGACY);p.add("nexus.auth.token-file",FILE::toString);
        p.add("nexus.memory.mcp.enabled",()->true);p.add("nexus.memory.mcp.token",()->"legacy-memory-credential-fixture-long-enough");
        p.add("nexus.mqtt.uri",()->"tcp://127.0.0.1:1");p.add("nexus.mqtt.password",()->"fixture-password");p.add("nexus.mqtt.allow-insecure",()->true);
        p.add("nexus.bridge.enabled",()->true);p.add("nexus.bridge.url",()->"http://127.0.0.1:1");p.add("nexus.bridge.token",()->"bridge-isolated-credential-fixture-long-enough");
        p.add("nexus.halo.enabled",()->true);p.add("nexus.halo.url",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("nexus.halo.token",()->"fixture-halo-key");p.add("nexus.halo.allow-insecure",()->true);p.add("nexus.halo.poll-ms",()->600000);
    }
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/devices"))
            .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> mcp(String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp/memory"))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version","2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),HttpResponse.BodyHandlers.ofString());
    }
    @BeforeEach void reset()throws Exception {replace(OLD,null,0);}
    @AfterAll static void cleanup()throws Exception {PROVIDER.stop(0);Files.deleteIfExists(FILE);Files.deleteIfExists(DIRECTORY);}
    @Test void adminRotationOverlapsThenExpiresWithoutRestartOrLegacyFallback()throws Exception {
        assertThat(request(OLD).statusCode()).isEqualTo(200);
        assertThat(request(LEGACY).statusCode()).isEqualTo(401);
        assertThat(request(NEXT).statusCode()).isEqualTo(401);
        replace(NEXT,OLD,Instant.now().plusSeconds(120).getEpochSecond());
        assertThat(request(OLD).statusCode()).isEqualTo(200);
        assertThat(request(NEXT).statusCode()).isEqualTo(200);
        replace(NEXT,OLD,Instant.now().minusSeconds(1).getEpochSecond());
        assertThat(request(OLD).statusCode()).isEqualTo(401);
        assertThat(request(NEXT).statusCode()).isEqualTo(200);
        assertThat(request(MEMORY).statusCode()).isEqualTo(401);
    }
    @Test void memoryRotationAndRevocationPreserveTheAdminBoundary()throws Exception {
        assertThat(mcp(MEMORY).statusCode()).isEqualTo(200);
        var value=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Files.readAllBytes(FILE));
        value.put("revision",REVISION.incrementAndGet());
        value.set("memory",JSON.valueToTree(Map.of("current",credential("memory-next",NEXT_MEMORY),
            "previous",Map.of("id","memory-old","token",MEMORY,"expiresAt",Instant.now().plusSeconds(120).getEpochSecond()))));publish(value);
        assertThat(mcp(MEMORY).statusCode()).isEqualTo(200);
        assertThat(mcp(NEXT_MEMORY).statusCode()).isEqualTo(200);
        assertThat(mcp(OLD).statusCode()).isEqualTo(401);
        assertThat(request(NEXT_MEMORY).statusCode()).isEqualTo(401);
        value.put("revision",REVISION.incrementAndGet());value.set("memory",JSON.valueToTree(Map.of("current",credential("memory-next",NEXT_MEMORY))));publish(value);
        assertThat(mcp(MEMORY).statusCode()).isEqualTo(401);
        assertThat(mcp(NEXT_MEMORY).statusCode()).isEqualTo(200);
        assertThat(request(OLD).statusCode()).isEqualTo(200);
    }
    @Test void tokenRotationAndRestartKeepExistingToolOwnership()throws Exception {
        var prepared=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/tool-jobs"))
            .header("Authorization","Bearer "+OLD).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("requestKey",UUID.randomUUID(),"toolId","app.open","arguments",Map.of("applicationId","fixture-app")))))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertThat(prepared.statusCode()).withFailMessage(prepared.body()).isEqualTo(201);
        String id=JSON.readTree(prepared.body()).path("jobId").asText();
        replace(NEXT,null,0);
        try(var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(NexusCore.class).registerShutdownHook(false).run(
            "--server.port=0","--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),
            "--nexus.auth.token-file="+FILE,"--nexus.admin-token=changed-env-credential-fixture-long-enough",
            "--nexus.mqtt.uri=tcp://127.0.0.1:1","--nexus.mqtt.allow-insecure=true","--nexus.mqtt.password=fixture-password")) {
            String otherPort=restarted.getEnvironment().getProperty("local.server.port");
            var read=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+otherPort+"/api/v1/tool-jobs/"+id))
                .header("Authorization","Bearer "+NEXT).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).withFailMessage(read.body()).isEqualTo(200);
            assertThat(JSON.readTree(read.body()).path("state").asText()).isEqualTo("awaiting_confirmation");
        }
    }
    @Test void revocationDoesNotInterruptAnAlreadyAuthorizedRequest()throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);STARTED.set(started);RELEASE.set(release);
        var pending=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/connectors/halo/poll"))
            .header("Authorization","Bearer "+OLD).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(started.await(4,TimeUnit.SECONDS)).isTrue();replace(NEXT,null,0);
            assertThat(request(OLD).statusCode()).isEqualTo(401);assertThat(request(NEXT).statusCode()).isEqualTo(200);
            release.countDown();assertThat(pending.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }finally{release.countDown();STARTED.set(null);RELEASE.set(null);}
    }
    @Test void invalidMissingAndUnsafeFilesFailClosedThenRecover()throws Exception {
        assertThat(request(OLD).statusCode()).isEqualTo(200);
        Files.writeString(FILE,"{\"format\":1,\"format\":1,\"secret\":\""+OLD+"\"}");
        var malformed=request(OLD);assertThat(malformed.statusCode()).isEqualTo(503);
        assertThat(malformed.body()).isEqualTo("{\"error\":\"authentication_configuration_unavailable\"}");
        assertThat(mcp(MEMORY).statusCode()).isEqualTo(503);
        replace(OLD,null,0);Files.setPosixFilePermissions(FILE,PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(request(OLD).statusCode()).isEqualTo(503);
        Files.setPosixFilePermissions(FILE,PosixFilePermissions.fromString("rw-------"));Files.delete(FILE);
        assertThat(request(OLD).statusCode()).isEqualTo(503);assertThat(request(LEGACY).statusCode()).isEqualTo(503);
        replace(OLD,null,0);assertThat(request(OLD).statusCode()).isEqualTo(200);
        Path original=DIRECTORY.resolve("original.json");Files.move(FILE,original);Files.createSymbolicLink(FILE,original.getFileName());
        try{assertThat(request(OLD).statusCode()).isEqualTo(503);}
        finally{Files.delete(FILE);Files.move(original,FILE);}
    }
    @Test void staleRevisionsSameRevisionChangesAndCrossScopeReuseAreRejected()throws Exception {
        assertThat(request(OLD).statusCode()).isEqualTo(200);byte[] old=Files.readAllBytes(FILE);
        replace(NEXT,null,0);assertThat(request(NEXT).statusCode()).isEqualTo(200);
        byte[] current=Files.readAllBytes(FILE);Files.write(FILE,old);assertThat(request(OLD).statusCode()).isEqualTo(503);
        Files.write(FILE,current);Files.writeString(FILE," ",StandardOpenOption.APPEND);assertThat(request(NEXT).statusCode()).isEqualTo(503);
        replace(OLD,null,0);var reused=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Files.readAllBytes(FILE));
        reused.put("revision",REVISION.incrementAndGet());reused.set("memory",JSON.valueToTree(Map.of("current",credential("memory-reused",OLD))));publish(reused);
        assertThat(request(OLD).statusCode()).isEqualTo(503);
        replace(OLD,null,0);assertThat(request(OLD).statusCode()).isEqualTo(200);
        var duplicate=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/devices"))
            .header("Authorization","Bearer "+OLD).header("Authorization","Bearer "+OLD).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(duplicate.statusCode()).isEqualTo(401);
    }
    @Test void realCredentialCliRotatesAndExportsForTheRunningHttpServer()throws Exception {
        Path output=DIRECTORY.resolve("memory-client.json");Path lock=FILE.resolveSibling(FILE.getFileName()+".lock");
        try {
            var rotate=new ProcessBuilder("python3","tools/credential_store.py","rotate","--file",FILE.toString(),"--scope","memory",
                "--expected-revision",Long.toString(REVISION.get()),"--grace-seconds","120").redirectErrorStream(true).start();
            assertThat(rotate.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(rotate.exitValue()).isZero();
            REVISION.set(JSON.readTree(Files.readAllBytes(FILE)).path("revision").asLong());
            var export=new ProcessBuilder("python3","tools/credential_store.py","export-memory","--file",FILE.toString(),"--output",output.toString()).redirectErrorStream(true).start();
            assertThat(export.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(export.exitValue()).isZero();
            String next=JSON.readTree(Files.readAllBytes(output)).path("token").asText();
            assertThat(mcp(MEMORY).statusCode()).isEqualTo(200);assertThat(mcp(next).statusCode()).isEqualTo(200);
            assertThat(request(next).statusCode()).isEqualTo(401);assertThat(request(OLD).statusCode()).isEqualTo(200);
            assertThat(new String(export.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(next,OLD,MEMORY);
        }finally{Files.deleteIfExists(output);Files.deleteIfExists(lock);}
    }
}
