package dev.nexus.core.ai;

import dev.nexus.core.connector.HttpBodies;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Resource operations are restricted to this user's public-comment folder. */
@Component
public class OpenVikingClient {
    private final boolean enabled;
    private final URI base;
    private final String key, account, user, peer, prefix,identity;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OpenVikingClient(ObjectMapper json, @Value("${nexus.memory.enabled:false}") boolean enabled,
        @Value("${nexus.memory.url:}") String url, @Value("${nexus.memory.api-key:}") String key,
        @Value("${nexus.memory.account:default}") String account, @Value("${nexus.memory.user:default}") String user,
        @Value("${nexus.memory.peer:nexus-pet}") String peer) {
        this.json = json; this.enabled = enabled; this.key = key; this.account = account; this.user = user; this.peer = peer;
        base = url.isBlank() ? null : URI.create(url.replaceAll("/+$", "") + "/");
        if (base != null && (!CommentModel.safeEndpoint(base) || base.getQuery() != null || base.getFragment() != null)) throw new IllegalArgumentException("invalid_memory_endpoint");
        for (String part : List.of(account, user, peer)) if (!part.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("invalid_memory_identity");
        prefix = "viking://user/" + user + "/resources/nexus-pet/public-comments/";
        // Pin the service and principal; rotating a key may keep the identity,
        // switching account/peer/server must not grant access to old copies.
        identity=AnalysisService.hash((base==null?"":base.toString())+"\n"+account+"\n"+user+"\n"+peer);
    }
    public boolean configured() { return enabled && base != null && !key.isBlank(); }
    public String identity() {return identity;}
    public String uri(UUID message) {return prefix+message+".md";}
    /** Search results are candidates only. Never expose abstracts or follow a
     * returned URI; ownership and source visibility must be checked locally. */
    public List<UUID> find(String query,int limit) {
        if(!configured())throw new AnalysisFailure("memory_not_configured");
        var request=HttpRequest.newBuilder(base.resolve("api/v1/search/find")).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").header("X-API-Key",key)
            .header("X-OpenViking-Account",account).header("X-OpenViking-User",user).header("X-OpenViking-Actor-Peer",peer)
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("query",query,"target_uri",prefix,
                "limit",limit,"level",List.of(2),"context_type",List.of("resource"),
                "tags",List.of("project=nexus-pet","data_class=public_comment"))))).build();
        try {
            var response=http.send(request,HttpBodies.limited(128*1024));
            var root=json.readTree(response.body());
            if(response.statusCode()!=200||!"ok".equals(root.path("status").asText())||!root.path("result").path("resources").isArray())
                throw new AnalysisFailure("memory_search_failed");
            var hits=root.path("result").path("resources");
            if(hits.size()>50)throw new AnalysisFailure("memory_search_failed");
            var ids=new LinkedHashSet<UUID>();
            for(var hit:hits) {
                String value=hit.path("uri").asText();
                if(!value.startsWith(prefix)||!value.endsWith(".md")||hit.path("level").asInt(-1)!=2||
                    !"resource".equals(hit.path("context_type").asText()))continue;
                String suffix=value.substring(prefix.length(),value.length()-3);
                try {var id=UUID.fromString(suffix);if(suffix.equals(id.toString()))ids.add(id);}
                catch(IllegalArgumentException ignored) {}
                if(ids.size()>=limit)break;
            }
            return new ArrayList<>(ids);
        }catch(AnalysisFailure e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AnalysisFailure("memory_interrupted");}
        catch(Exception e){throw new AnalysisFailure("memory_search_failed");}
    }
    public String write(UUID message, String content) {
        if (!configured()) throw new AnalysisFailure("memory_not_configured");
        String uri = uri(message);
        var request = HttpRequest.newBuilder(base.resolve("api/v1/content/write")).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").header("X-API-Key", key)
            .header("X-OpenViking-Account", account).header("X-OpenViking-User", user).header("X-OpenViking-Actor-Peer", peer)
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("uri", uri, "content", content,
                "mode", "replace", "wait", true, "tags", List.of("project=nexus-pet", "data_class=public_comment"))))).build();
        try {
            var response = http.send(request, HttpBodies.limited(64 * 1024));
            if (response.statusCode() != 200 || !"ok".equals(json.readTree(response.body()).path("status").asText())) throw new AnalysisFailure("memory_write_failed");
            return uri;
        } catch (AnalysisFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AnalysisFailure("memory_interrupted"); }
        catch (Exception e) { throw new AnalysisFailure("memory_unavailable"); }
    }
    public void delete(UUID message) {
        if(!configured())throw new AnalysisFailure("memory_not_configured");
        var request=HttpRequest.newBuilder(base.resolve("api/v1/fs?uri="+URLEncoder.encode(uri(message),StandardCharsets.UTF_8)+"&recursive=false"))
            .timeout(Duration.ofSeconds(8)).header("X-API-Key",key).header("X-OpenViking-Account",account)
            .header("X-OpenViking-User",user).header("X-OpenViking-Actor-Peer",peer).DELETE().build();
        try {
            var response=http.send(request,HttpBodies.limited(64*1024));
            if(response.statusCode()!=200||!"ok".equals(json.readTree(response.body()).path("status").asText()))
                throw new AnalysisFailure("memory_delete_failed");
        }catch(AnalysisFailure e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AnalysisFailure("memory_interrupted");}
        catch(Exception e){throw new AnalysisFailure("memory_delete_failed");}
    }
}
