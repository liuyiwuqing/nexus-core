package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** Real HTTP/PostgreSQL; external providers are isolated HTTP boundary fixtures. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "nexus.retention.enabled=true","nexus.retention.cron=-","nexus.memory.tick-ms=100","nexus.memory.maintenance-ms=100"})
class MemoryLifecycleFlowTest {
    static final String TOKEN="memory-lifecycle-test-token-at-least-32-characters";
    static final String MCP_TOKEN="voice-memory-only-test-token-at-least-32-characters";
    static final String PREFIX="viking://user/default/resources/nexus-pet/public-comments/";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    static final ObjectMapper MAPPER=new ObjectMapper();
    static final AtomicReference<String> CONTENT=new AtomicReference<>("public-original-body");
    static final AtomicBoolean PUBLIC=new AtomicBoolean(true),SOURCE_DOWN=new AtomicBoolean(),DELETE_DOWN=new AtomicBoolean(),SEARCH_DOWN=new AtomicBoolean();
    static final ConcurrentMap<String,String> COPIES=new ConcurrentHashMap<>();
    static final AtomicReference<String> LAST_URI=new AtomicReference<>("");
    static final AtomicReference<CountDownLatch> WRITE_STARTED=new AtomicReference<>(),WRITE_RELEASE=new AtomicReference<>();
    static final HttpServer PROVIDER=provider();
    static HttpServer provider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",ex->{
                try {
                    String path=ex.getRequestURI().getPath();Object body;
                    if(path.endsWith("/comments")) {
                        if(SOURCE_DOWN.get()){ex.sendResponseHeaders(503,-1);return;}
                        var spec=Map.of("approved",true,"hidden",false,"approvedTime",Instant.now().minusSeconds(30).toString(),"content",CONTENT.get());
                        var comment=Map.of("apiVersion","content.halo.run/v1alpha1","kind","Comment","metadata",Map.of("name","public-1"),"spec",spec);
                        var subject=Map.of("apiVersion","content.halo.run/v1alpha1","kind","Post","spec",Map.of("publish",true,"deleted",false,"visible",PUBLIC.get()?"PUBLIC":"PRIVATE"),"status",Map.of("phase","PUBLISHED"));
                        body=Map.of("items",List.of(Map.of("comment",comment,"subject",subject,"permalink","/post#public-1")),"total",1,"hasNext",false);
                    }else if(path.endsWith("/chat/completions")) {
                        ex.getRequestBody().readAllBytes();
                        String output=MAPPER.writeValueAsString(Map.of("summary","verified-local-summary","category","other","importance","normal","hasQuestion",false,"replyDraft",""));
                        body=Map.of("choices",List.of(Map.of("message",Map.of("content",output))));
                    }else {
                        assertThat(ex.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("fixture-memory-key");
                        assertThat(ex.getRequestHeaders().getFirst("X-OpenViking-Actor-Peer")).isEqualTo("nexus-pet-tests");
                        if(path.endsWith("/content/write")) {
                            var input=MAPPER.readTree(ex.getRequestBody().readAllBytes());String uri=input.path("uri").asText();
                            assertThat(uri).startsWith(PREFIX);COPIES.put(uri,input.path("content").asText());LAST_URI.set(uri);
                            var started=WRITE_STARTED.get();var release=WRITE_RELEASE.get();if(started!=null)started.countDown();
                            if(release!=null)try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                            body=Map.of("status","ok","result",Map.of());
                        }else if(path.endsWith("/search/find")) {
                            ex.getRequestBody().readAllBytes();if(SEARCH_DOWN.get()){ex.sendResponseHeaders(503,-1);return;}
                            // A stale upstream index continues returning the old URI after deletion.
                            body=Map.of("status","ok","result",Map.of("resources",List.of(Map.of("uri",LAST_URI.get(),"level",2,"context_type","resource","abstract","never-return-this-abstract"))));
                        }else if(path.equals("/api/v1/fs")) {
                            assertThat(ex.getRequestMethod()).isEqualTo("DELETE");
                            String query=URLDecoder.decode(ex.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
                            assertThat(query).contains("uri="+LAST_URI.get(),"recursive=false");
                            if(DELETE_DOWN.get()){ex.sendResponseHeaders(503,-1);return;}
                            COPIES.remove(LAST_URI.get());body=Map.of("status","ok","result",Map.of());
                        }else {ex.sendResponseHeaders(404,-1);return;}
                    }
                    byte[] bytes=MAPPER.writeValueAsBytes(body);ex.getResponseHeaders().add("Content-Type","application/json");
                    ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);
                }finally{ex.close();}
            });server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://127.0.0.1:1");p.add("nexus.mqtt.password",()->"fixture-password");
        p.add("nexus.mqtt.allow-insecure",()->true);p.add("nexus.mqtt.ca-cert",()->"");
        String url="http://127.0.0.1:"+PROVIDER.getAddress().getPort();
        p.add("nexus.halo.enabled",()->true);p.add("nexus.halo.url",()->url);p.add("nexus.halo.token",()->"fixture-halo-key");
        p.add("nexus.halo.allow-insecure",()->true);p.add("nexus.halo.since",()->"1970-01-01T00:00:00Z");
        p.add("nexus.ai.enabled",()->true);p.add("nexus.ai.base-url",()->url+"/v1");p.add("nexus.ai.model",()->"fixture-model");p.add("nexus.ai.tick-ms",()->100);
        p.add("nexus.memory.enabled",()->true);p.add("nexus.memory.url",()->url);p.add("nexus.memory.api-key",()->"fixture-memory-key");p.add("nexus.memory.peer",()->"nexus-pet-tests");
        p.add("nexus.memory.mcp.enabled",()->true);p.add("nexus.memory.mcp.token",()->MCP_TOKEN);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.transaction.PlatformTransactionManager fixtureTransactions;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String body)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path))
            .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path)throws Exception{return MAPPER.readTree(request("GET",path,"").body());}
    JsonNode search()throws Exception {
        var response=request("POST","memory/search","{\"query\":\"public comment\"}");assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }
    String analyzed()throws Exception {
        assertThat(request("POST","connectors/halo/poll","").statusCode()).isEqualTo(200);
        String id=get("messages").path("items").get(0).path("messageId").asText();
        assertThat(request("POST","messages/"+id+"/analysis","").statusCode()).isEqualTo(202);await("messages/"+id+"/analysis","status","succeeded");return id;
    }
    void save(String id)throws Exception {
        assertThat(request("POST","messages/"+id+"/memory","").statusCode()).isEqualTo(202);await("messages/"+id+"/analysis","memoryStatus","stored");
    }
    void await(String path,String field,String expected)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);JsonNode last;
        do {last=get(path);if(last.path(field).asText().equals(expected))return;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertThat(last.path(field).asText()).withFailMessage(last.toString()).isEqualTo(expected);
    }
    @BeforeEach void reset() {
        // TRUNCATE's exclusive table locks can deadlock with scheduled joins.
        // Reset fixture rows transactionally while normal readers keep running.
        new org.springframework.transaction.support.TransactionTemplate(fixtureTransactions).executeWithoutResult(status->{
            db.queryForList("SELECT message_id FROM inbox_messages FOR UPDATE");
            db.execute("DELETE FROM memory_copies");db.execute("DELETE FROM comment_analysis");
            db.execute("DELETE FROM deliveries");db.execute("DELETE FROM inbox_messages");
            db.execute("DELETE FROM automation_runs");db.execute("DELETE FROM core_events");db.execute("DELETE FROM retention_audit");
        });
        PUBLIC.set(true);CONTENT.set("public-original-body");SOURCE_DOWN.set(false);DELETE_DOWN.set(false);SEARCH_DOWN.set(false);
        COPIES.clear();LAST_URI.set("");WRITE_STARTED.set(null);WRITE_RELEASE.set(null);
    }
    @AfterAll static void close(){var release=WRITE_RELEASE.get();if(release!=null)release.countDown();PROVIDER.stop(0);}
    HttpResponse<String> mcp(String body,String token,String... headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp/memory"))
            .timeout(Duration.ofSeconds(16)).header("Content-Type","application/json")
            .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version","2025-06-18");
        if(token!=null)b.header("Authorization","Bearer "+token);
        for(int i=0;i<headers.length;i+=2)b.setHeader(headers[i],headers[i+1]);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode rpc(String method,String params)throws Exception {
        var response=mcp("{\"jsonrpc\":\"2.0\",\"id\":\"voice-1\",\"method\":\""+method+"\",\"params\":"+params+"}",MCP_TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return MAPPER.readTree(response.body());
    }
    @Test void voiceMemoryCredentialsCannotReadAdminApisAndAdminCredentialsCannotEnterTheVoiceEndpoint()throws Exception {
        String initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}";
        assertThat(mcp(initialize,null).statusCode()).isEqualTo(401);
        assertThat(mcp(initialize,TOKEN).statusCode()).isEqualTo(401);
        var accepted=mcp(initialize,MCP_TOKEN);assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(accepted.body()).path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(mcp(initialize,MCP_TOKEN,"Origin","https://untrusted.invalid").statusCode()).isEqualTo(403);
        for(String path:List.of("messages","devices","memory","memory/search","tool-jobs")) {
            var denied=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path))
                .header("Authorization","Bearer "+MCP_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
        }
        assertThat(mcp("{}",MCP_TOKEN,"MCP-Protocol-Version","unsupported").statusCode()).isEqualTo(400);
        assertThat(mcp(initialize,MCP_TOKEN,"Accept","text/plain").statusCode()).isEqualTo(406);
        assertThat(mcp(" ".repeat(32769),MCP_TOKEN).statusCode()).isEqualTo(413);
        assertThat(mcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",MCP_TOKEN).statusCode()).isEqualTo(202);
        var get=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp/memory"))
            .header("Authorization","Bearer "+MCP_TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
    }
    @Test void voiceMemoryToolsExposeOnlyVerifiedResultsAndKeepTheExistingInvalidationPolicy()throws Exception {
        String id=analyzed();save(id);
        var tools=rpc("tools/list","{}").path("result").path("tools");assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("memory.search");
        // Retrieval may queue the previously approved source-invalidation cleanup.
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        String args="{\"name\":\"memory.search\",\"arguments\":{\"query\":\"public comment\",\"limit\":1}}";
        var response=rpc("tools/call",args);assertThat(response.path("id").asText()).isEqualTo("voice-1");
        var result=response.path("result");assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("items").get(0).path("content").asText()).isEqualTo("public-original-body");
        assertThat(MAPPER.readTree(result.path("content").get(0).path("text").asText())).isEqualTo(result.path("structuredContent"));
        assertThat(response.toString()).doesNotContain("fixture-memory-key","fixture-halo-key","never-return-this-abstract",PREFIX);
        SOURCE_DOWN.set(true);var partial=rpc("tools/call",args).path("result").path("structuredContent");
        assertThat(partial.path("items")).isEmpty();assertThat(partial.path("partial").asBoolean()).isTrue();SOURCE_DOWN.set(false);
        SEARCH_DOWN.set(true);var unavailable=rpc("tools/call",args).path("result");
        assertThat(unavailable.path("isError").asBoolean()).isTrue();assertThat(unavailable.path("content").get(0).path("text").asText()).isEqualTo("memory_search_unavailable");SEARCH_DOWN.set(false);
        DELETE_DOWN.set(true);PUBLIC.set(false);
        assertThat(rpc("tools/call",args).path("result").path("structuredContent").path("items")).isEmpty();
        assertThat(get("memory").path("pendingDeletions").asInt()).isEqualTo(1);
    }
    @Test void voiceMemoryRejectsArbitraryToolsScopesMalformedRpcAndInvalidArguments()throws Exception {
        for(String params:List.of("{\"name\":\"memory.delete\",\"arguments\":{}}",
            "{\"name\":\"memory.search\",\"arguments\":{\"query\":\"q\",\"target_uri\":\"viking://private\"}}",
            "{\"name\":\"memory.search\",\"arguments\":{\"query\":\"   \"}}",
            "{\"name\":\"memory.search\",\"arguments\":{\"query\":\"q\",\"limit\":1.5}}",
            "{\"name\":\"memory.search\",\"arguments\":{\"query\":\"q\",\"limit\":6}}"))
            assertThat(rpc("tools/call",params).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(rpc("resources/read","{\"uri\":\"viking://private\"}").path("error").path("code").asInt()).isEqualTo(-32601);
        for(String body:List.of("[]","{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")) {
            var response=mcp(body,MCP_TOKEN);assertThat(response.statusCode()).isEqualTo(400);
            assertThat(MAPPER.readTree(response.body()).path("error").path("code").asInt()).isEqualTo(-32600);
        }
        for(String body:List.of("{","{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}","{} {}"))
            assertThat(MAPPER.readTree(mcp(body,MCP_TOKEN).body()).path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(mcp("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"memory.search\",\"arguments\":{\"query\":\"q\"}}}",MCP_TOKEN).statusCode()).isEqualTo(400);
        var wrongEncoding=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp/memory"))
            .header("Authorization","Bearer "+MCP_TOKEN).header("Content-Type","application/json")
            .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version","2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofByteArray("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_16))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(wrongEncoding.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(wrongEncoding.body()).path("error").path("code").asInt()).isEqualTo(-32700);
    }
    @Test void theVoiceStdioProcessCanDiscoverAndQueryTheRealCoreEndpoint()throws Exception {
        String id=analyzed();save(id);
        var processBuilder=new ProcessBuilder("python3","tools/memory_mcp_stdio.py");
        processBuilder.environment().put("NEXUS_MEMORY_MCP_URL","http://127.0.0.1:"+port+"/mcp/memory");
        processBuilder.environment().put("NEXUS_MEMORY_MCP_TOKEN",MCP_TOKEN);
        Process process=processBuilder.start();
        try {
            try(var input=process.outputWriter(StandardCharsets.UTF_8)) {
                input.write("""
                    {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-fixture","version":"1"}}}
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    {"jsonrpc":"2.0","id":"discover","method":"tools/list"}
                    {"jsonrpc":"2.0","id":"query","method":"tools/call","params":{"name":"memory.search","arguments":{"query":"public comment","limit":1}}}
                    """);
            }
            assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
            String errors=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(errors).isZero();
            var lines=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).lines().toList();
            assertThat(lines).hasSize(3);assertThat(errors).isEmpty();
            assertThat(MAPPER.readTree(lines.get(1)).path("result").path("tools").get(0).path("name").asText()).isEqualTo("memory.search");
            var result=MAPPER.readTree(lines.get(2));assertThat(result.path("id").asText()).isEqualTo("query");
            assertThat(result.path("result").path("structuredContent").path("items").get(0).path("content").asText()).isEqualTo("public-original-body");
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="NEXUS_VOICE_BRIDGE_TEST",matches="true")
    void thePinnedVoiceBridgeRevalidatesPublicContentAcrossTlsReconnects()throws Exception {
        String id=analyzed();save(id);
        var root=java.nio.file.Path.of("../04-ai-desktop-pet").toAbsolutePath().normalize();
        var directory=java.nio.file.Path.of(System.getenv().getOrDefault("NEXUS_VOICE_BRIDGE_DIRECTORY",root.resolve("build/memory-voice").toString()));
        for(boolean expectEmpty:List.of(false,true)) {
            PUBLIC.set(!expectEmpty);
            var command=new ArrayList<>(List.of(directory.resolve(".venv/bin/python").toString(),
                root.resolve("integration/tests/voice_memory_probe.py").toString(),"--bridge-directory",directory.toString()));
            if(expectEmpty)command.add("--expect-empty");
            var builder=new ProcessBuilder(command);
            String path=System.getenv("PATH");builder.environment().clear();if(path!=null)builder.environment().put("PATH",path);
            builder.environment().put("NEXUS_MEMORY_MCP_URL","http://127.0.0.1:"+port+"/mcp/memory");
            builder.environment().put("NEXUS_MEMORY_MCP_TOKEN",MCP_TOKEN);
            Process process=builder.start();
            try {
                assertThat(process.waitFor(45,TimeUnit.SECONDS)).withFailMessage("local voice probe timed out").isTrue();
                String errors=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
                String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                assertThat(process.exitValue()).withFailMessage(errors).isZero();
                var report=MAPPER.readTree(output);assertThat(report.path("status").asText()).isEqualTo("passed");
                assertThat(report.path("reconnected").asBoolean()).isTrue();
                assertThat(report.path("empty").asBoolean()).isEqualTo(expectEmpty);
            }finally {
                // Only this isolated probe's descendants; no unrelated services.
                var children=process.descendants().toList();children.forEach(ProcessHandle::destroy);
                if(process.isAlive())process.destroy();
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if(process.isAlive())process.destroyForcibly();
            }
        }
    }
    @Test void changedSourceIsHiddenWhileDeletionRetriesAndDoesNotReturnWhenTheSourceReappears()throws Exception {
        String id=analyzed();save(id);
        assertThat(search().path("items")).hasSize(1);
        for(String body:List.of("{\"query\":\"q\",\"limit\":0}","{\"query\":\"q\",\"limit\":6}","{\"query\":\""+"q".repeat(257)+"\"}"))
            assertThat(request("POST","memory/search",body).statusCode()).isEqualTo(400);
        SEARCH_DOWN.set(true);assertThat(request("POST","memory/search","{\"query\":\"q\"}").statusCode()).isEqualTo(502);SEARCH_DOWN.set(false);
        SOURCE_DOWN.set(true);var partial=search();assertThat(partial.path("items")).isEmpty();assertThat(partial.path("partial").asBoolean()).isTrue();
        assertThat(get("memory").path("storedCopies").asInt()).isEqualTo(1);SOURCE_DOWN.set(false);
        DELETE_DOWN.set(true);CONTENT.set("public-edited-body");assertThat(search().path("items")).isEmpty();await("memory","deletionErrors","1");
        assertThat(get("memory").path("pendingDeletions").asInt()).isEqualTo(1);assertThat(COPIES).hasSize(1);
        assertThat(request("POST","messages/"+id+"/memory","").statusCode()).isEqualTo(409);
        CONTENT.set("public-original-body");assertThat(search().path("items")).isEmpty();
        DELETE_DOWN.set(false);db.update("UPDATE memory_copies SET next_attempt_at=now()");await("memory","deletedCopies","1");
        assertThat(COPIES).isEmpty();assertThat(search().path("items")).isEmpty();
    }
    @Test void aCommentChangedBeforeItsFirstSaveCanBeReanalyzedAndExplicitlySaved()throws Exception {
        String id=analyzed();CONTENT.set("public-edited-body");
        assertThat(request("POST","messages/"+id+"/memory","").statusCode()).isEqualTo(202);
        await("messages/"+id+"/analysis","status","blocked");
        assertThat(COPIES).isEmpty();
        assertThat(request("POST","messages/"+id+"/analysis/retry","").statusCode()).isEqualTo(202);
        await("messages/"+id+"/analysis","status","succeeded");save(id);
        assertThat(search().path("items").get(0).path("content").asText()).isEqualTo("public-edited-body");
    }
    @Test void backgroundRevalidationDeletesWithdrawnContentWithoutAQuery()throws Exception {
        String id=analyzed();save(id);PUBLIC.set(false);
        db.update("UPDATE memory_copies SET checked_at=now()-interval '2 minutes'");await("memory","deletedCopies","1");
        assertThat(COPIES).isEmpty();assertThat(get("messages/"+id+"/analysis").path("memoryError").asText()).isEqualTo("no_longer_public");
    }
    @Test void explicitCopiesRemainVerifiableAfterLocalRetentionWithoutKeepingLocalCommentBodies()throws Exception {
        String id=analyzed();save(id);
        db.update("UPDATE inbox_messages SET received_at=now()-interval '31 days' WHERE message_id=?",UUID.fromString(id));
        assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}").statusCode()).isEqualTo(200);
        assertThat(get("messages").path("items")).isEmpty();
        var found=search().path("items");assertThat(found).hasSize(1);
        assertThat(found.get(0).path("content").asText()).isEqualTo("public-original-body");
        assertThat(found.get(0).path("summary").asText()).isEmpty();
        PUBLIC.set(false);db.update("UPDATE memory_copies SET checked_at=now()-interval '2 minutes'");
        await("memory","deletedCopies","1");assertThat(COPIES).isEmpty();
    }
    @Test void anUnavailableSourceIdentityHidesResultsWithoutTreatingItAsAWithdrawnComment()throws Exception {
        String id=analyzed();save(id);
        db.update("UPDATE memory_copies SET connector_id='previous-connector-identity' WHERE message_id=?",UUID.fromString(id));
        var result=search();assertThat(result.path("items")).isEmpty();assertThat(result.path("partial").asBoolean()).isTrue();
        assertThat(get("memory").path("storedCopies").asInt()).isEqualTo(1);
        assertThat(get("memory").path("pendingDeletions").asInt()).isZero();assertThat(COPIES).hasSize(1);
    }
    @Test void aCopyFromAnotherIdentityIsHiddenAndNeverDeletedWithTheCurrentIdentity()throws Exception {
        String id=analyzed();save(id);
        db.update("UPDATE memory_copies SET memory_uri='viking://user/another/resources/private.md',state='delete_pending' WHERE message_id=?",UUID.fromString(id));
        await("memory","deletionErrors","1");
        assertThat(get("memory").path("pendingDeletions").asInt()).isEqualTo(1);
        assertThat(COPIES).hasSize(1);assertThat(search().path("items")).isEmpty();
    }
    @Test void theSameUserUriFromADifferentAccountOrPeerCannotBeRetrievedOrDeleted()throws Exception {
        String id=analyzed();save(id);
        db.update("UPDATE memory_copies SET memory_identity='previous-account/default/another-peer' WHERE message_id=?",UUID.fromString(id));
        assertThat(search().path("items")).isEmpty();
        assertThat(get("memory").path("identityBlockedCopies").asInt()).isEqualTo(1);
        db.update("UPDATE memory_copies SET state='delete_pending' WHERE message_id=?",UUID.fromString(id));
        await("memory","deletionErrors","1");assertThat(COPIES).hasSize(1);
    }
    @Test void retentionCanRemoveTheMessageWhileAnExportIsInFlightWithoutLosingItsDeletion()throws Exception {
        String id=analyzed();var started=new CountDownLatch(1);var release=new CountDownLatch(1);WRITE_STARTED.set(started);WRITE_RELEASE.set(release);
        try {
            assertThat(request("POST","messages/"+id+"/memory","").statusCode()).isEqualTo(202);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE inbox_messages SET received_at=now()-interval '31 days' WHERE message_id=?",UUID.fromString(id));
            assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}").statusCode()).isEqualTo(200);
            assertThat(get("messages").path("items")).isEmpty();assertThat(get("memory").path("pendingDeletions").asInt()).isEqualTo(1);
        }finally{release.countDown();}
        await("memory","deletedCopies","1");assertThat(COPIES).isEmpty();assertThat(search().path("items")).isEmpty();
    }
}
