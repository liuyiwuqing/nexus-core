package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SQL only arranges aged fixtures; all outcomes are observed through authenticated HTTP. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"nexus.retention.enabled=true", "nexus.retention.cron=-"})
class RetentionFlowTest {
    static final String TOKEN="retention-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("nexus.admin-token",()->TOKEN);
        p.add("nexus.mqtt.uri",()->"tcp://127.0.0.1:1");p.add("nexus.mqtt.allow-insecure",()->true);
        p.add("nexus.mqtt.password",()->"retention-test-password");
        p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.halo.enabled",()->true);p.add("nexus.halo.url",()->"http://127.0.0.1:"+HALO.getAddress().getPort());
        p.add("nexus.halo.token",()->"retention-halo-fixture");p.add("nexus.halo.allow-insecure",()->true);
        p.add("nexus.halo.since",()->"1970-01-01T00:00:00Z");
        p.add("nexus.ai.enabled",()->true);p.add("nexus.ai.base-url",()->"http://127.0.0.1:"+MODEL.getAddress().getPort());
        p.add("nexus.ai.model",()->"retention-fixture");p.add("nexus.ai.tick-ms",()->"100");


    }
    static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> MODEL_STARTED=new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> MODEL_RELEASE=new java.util.concurrent.atomic.AtomicReference<>();
    static final com.sun.net.httpserver.HttpServer MODEL=modelServer();
    static com.sun.net.httpserver.HttpServer modelServer() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                exchange.getRequestBody().readAllBytes();
                var started=MODEL_STARTED.get();var release=MODEL_RELEASE.get();
                if(started!=null)started.countDown();
                if(release!=null)try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                var mapper=new ObjectMapper();
                String output=mapper.writeValueAsString(java.util.Map.of("summary","late-private-summary","category","other","importance","normal","hasQuestion",false,"replyDraft","late-private-reply"));
                byte[] bytes=mapper.writeValueAsBytes(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("message",java.util.Map.of("content",output)))));
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();return server;
        }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    static final com.sun.net.httpserver.HttpServer HALO=haloServer();
    static com.sun.net.httpserver.HttpServer haloServer() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/apis/api.console.halo.run/v1alpha1/comments",exchange->{
                String item="""
                    {"comment":{"apiVersion":"content.halo.run/v1alpha1","kind":"Comment",
                     "metadata":{"name":"old-public","creationTimestamp":"TIME"},
                     "spec":{"approved":true,"hidden":false,"approvedTime":"TIME","content":"old-public-body","owner":{"displayName":"visitor"}}},
                     "subject":{"apiVersion":"content.halo.run/v1alpha1","kind":"Post","spec":{"publish":true,"deleted":false,"visible":"PUBLIC"},"status":{"phase":"PUBLISHED"}}}
                    """.replace("TIME",java.time.Instant.now().minusSeconds(40*86400L).toString());
                byte[] bytes=("{\"items\":["+item+"],\"total\":1,\"hasNext\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();return server;
        }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    @org.junit.jupiter.api.AfterAll static void stopHalo(){HALO.stop(0);MODEL.stop(0);}
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    UUID message(int days,String marker) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,
                sender,original_url,priority,created_at,received_at,expires_at,metadata,data_class)
            VALUES(?,'retention-test','halo',?,'comment.created',?,?,?,?,'https://example.invalid/comment','normal',
                now()-interval '90 days',now()-(? * interval '1 day'),now()-interval '1 hour',?::jsonb,'public_comment')
            """,id,id.toString(),marker,marker,marker,marker,days,"{\"body\":\""+marker+"\"}");
        return id;
    }
    JsonNode listed(UUID id)throws Exception {
        for(var row:json.readTree(request("GET","messages?page=1&size=100","",TOKEN).body()).path("items"))
            if(row.path("messageId").asText().equals(id.toString()))return row;
        return null;
    }
    @org.junit.jupiter.api.BeforeEach void resetFixtures() {
        db.execute("TRUNCATE inbox_messages,memory_copies,retention_audit CASCADE");
    }
    @Test void expiredContentCannotBeReanalyzedOrExportedToMemory()throws Exception {
        UUID old=message(8,"retained-private-body");
        db.update("INSERT INTO comment_analysis(message_id,status,summary,reply_draft,content_hash) VALUES(?,'succeeded','retained-private-body','retained-private-body','hash')",old);
        for(String suffix:new String[]{"analysis","analysis/retry","memory"})
            assertThat(request("POST","messages/"+old+"/"+suffix,"",TOKEN).statusCode()).isEqualTo(410);
        assertThat(request("GET","messages/"+old+"/analysis","",TOKEN).body()).doesNotContain("retained-private-body");
        assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(200);
        for(String suffix:new String[]{"analysis","analysis/retry","memory"})
            assertThat(request("POST","messages/"+old+"/"+suffix,"",TOKEN).statusCode()).isEqualTo(410);
    }
    @Test void cleanupRollsBackTheWholeBatchWhenOneWriteFails()throws Exception {
        UUID old=message(8,"rollback-body"),gone=message(31,"rollback-metadata");
        db.execute("CREATE FUNCTION retention_fixture_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture_write_failure'; END $$");
        db.execute("CREATE TRIGGER retention_fixture_fail BEFORE UPDATE ON inbox_messages FOR EACH ROW EXECUTE FUNCTION retention_fixture_fail()");
        try {
            assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(500);
            assertThat(listed(old).path("content").asText()).isEqualTo("rollback-body");
            assertThat(listed(gone)).isNotNull();
            assertThat(json.readTree(request("GET","retention","",TOKEN).body()).path("history").isEmpty()).isTrue();
        } finally {
            db.execute("DROP TRIGGER retention_fixture_fail ON inbox_messages");
            db.execute("DROP FUNCTION retention_fixture_fail()");
        }
        assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(200);
        assertThat(listed(gone)).isNull();
    }
    @Test void cleanupIsBoundedAndReportsBusyWithoutChangingMessages()throws Exception {
        for(int i=0;i<201;i++)message(8,"batch-body");
        try(var connection=db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {statement.execute("SELECT pg_advisory_xact_lock(726384901)");}
            assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(409);
            assertThat(json.readTree(request("GET","retention","",TOKEN).body()).path("bodiesDue").asInt()).isEqualTo(201);
            connection.rollback();
        }
        var first=request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("bodiesPurged").asInt()).isEqualTo(200);
        var second=request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN);
        assertThat(json.readTree(second.body()).path("bodiesPurged").asInt()).isEqualTo(1);
    }
    @Test void automaticPollingDoesNotReimportOldCommentsButExplicitBackfillStillWorks()throws Exception {
        var poll=request("POST","connectors/halo/poll","",TOKEN);
        assertThat(poll.statusCode()).withFailMessage(poll.body()).isEqualTo(200);
        assertThat(json.readTree(poll.body()).path("received").asInt()).isZero();
        var imported=request("POST","connectors/halo/comments/old-public/import","",TOKEN);
        assertThat(imported.statusCode()).withFailMessage(imported.body()).isEqualTo(201);
        var id=UUID.fromString(json.readTree(imported.body()).path("messageId").asText());
        assertThat(listed(id).path("content").asText()).isEqualTo("old-public-body");
    }
    @Test void anAnalysisAlreadyInFlightCannotRestoreClearedContent()throws Exception {
        UUID id=message(0,"before-clear");
        db.update("UPDATE inbox_messages SET connector_id='halo-main',upstream_event_id='comment:old-public' WHERE message_id=?",id);
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        MODEL_STARTED.set(started);MODEL_RELEASE.set(release);
        try {
            assertThat(request("POST","messages/"+id+"/analysis","",TOKEN).statusCode()).isEqualTo(202);
            assertThat(started.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE inbox_messages SET received_at=now()-interval '8 days' WHERE message_id=?",id);
            assertThat(request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(200);
        }finally{release.countDown();MODEL_STARTED.set(null);MODEL_RELEASE.set(null);}
        Thread.sleep(250); // Allow the real worker to finish the previously blocked external HTTP response.
        assertThat(listed(id).path("summary").asText()).isEmpty();
        assertThat(request("GET","messages/"+id+"/analysis","",TOKEN).body()).doesNotContain("late-private");
    }
    @Test void previewDoesNotDeleteAndCleanupRemovesBodiesBeforeMetadata()throws Exception {
        UUID fresh=message(6,"keep-fresh"),old=message(8,"remove-private"),gone=message(31,"remove-entirely");
        db.update("INSERT INTO comment_analysis(message_id,status,summary,reply_draft,content_hash) VALUES(?,'succeeded','remove-private','remove-private','hash')",old);
        assertThat(request("GET","retention","","wrong-token").statusCode()).isEqualTo(401);
        var preview=request("GET","retention","",TOKEN);
        assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
        assertThat(json.readTree(preview.body()).path("bodyDays").asInt()).isEqualTo(7);
        assertThat(listed(old).path("content").asText()).isEqualTo("remove-private");
        assertThat(listed(gone)).isNotNull();
        assertThat(request("POST","retention/runs","{}",TOKEN).statusCode()).isEqualTo(400);
        var run=request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN);
        assertThat(run.statusCode()).withFailMessage(run.body()).isEqualTo(200);
        assertThat(listed(fresh).path("content").asText()).isEqualTo("keep-fresh");
        assertThat(listed(gone)).isNull();
        var redacted=listed(old);
        for(String key:new String[]{"title","content","summary","sender","originalUrl"})
            assertThat(redacted.path(key).asText()).isEmpty();
        assertThat(redacted.path("contentPurgedAt").isTextual()).isTrue();
        assertThat(redacted.path("metadata").size()).isZero();
        assertThat(request("GET","messages/"+old+"/analysis","",TOKEN).body()).doesNotContain("remove-private");
        var again=request("POST","retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body()).path("bodiesPurged").asInt()).isZero();
        assertThat(json.readTree(again.body()).path("messagesDeleted").asInt()).isZero();
        assertThat(request("GET","retention","",TOKEN).body()).doesNotContain("remove-private",TOKEN);
    }
}
