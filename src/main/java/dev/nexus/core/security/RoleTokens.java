package dev.nexus.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleTokens {
    public record Identity(String actor,String role){}
    private final JdbcTemplate db;
    public RoleTokens(JdbcTemplate db){this.db=db;}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Identity authenticate(String authorization){
        if(authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>300)return null;
        return authenticateFingerprint(hash(authorization.substring(7)));
    }
    public Identity authenticateFingerprint(String fingerprint){
        var rows=db.queryForList("SELECT token_id,role FROM role_tokens WHERE token_hash=? AND revoked_at IS NULL AND expires_at>now()",fingerprint);
        return rows.isEmpty()?null:new Identity(rows.getFirst().get("token_id").toString(),(String)rows.getFirst().get("role"));
    }
    public boolean ownsCredential(String token){return token!=null&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM role_tokens WHERE token_hash=?)",Boolean.class,hash(token)));}
    public List<Map<String,Object>> list(){return dev.nexus.core.web.ApiRows.normalize(db.queryForList("SELECT token_id AS \"tokenId\",name,role,version,expires_at AS \"expiresAt\",revoked_at AS \"revokedAt\",created_at AS \"createdAt\" FROM role_tokens ORDER BY created_at DESC LIMIT 100"));}
    @Transactional public Map<String,Object> issue(String name,String role,Instant expires){
        validate(name,role,expires);
        UUID id=UUID.randomUUID();String token=secret();
        db.update("INSERT INTO role_tokens(token_id,name,role,token_hash,expires_at) VALUES(?,?,?,?,?)",id,name,role,hash(token),Timestamp.from(expires));
        return Map.of("tokenId",id,"token",token,"role",role,"expiresAt",expires);
    }
    private static void validate(String name,String role,Instant expires){
        if(name==null||name.isBlank()||name.length()>80||!List.of("viewer","operator").contains(role==null?"":role)||expires==null||!expires.isAfter(Instant.now())||expires.isAfter(Instant.now().plusSeconds(366L*86400)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_role_token");
    }
    private static String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private Map<String,Object> editable(UUID id,int version){
        var rows=db.queryForList("SELECT version,revoked_at,expires_at FROM role_tokens WHERE token_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"role_token_not_found");
        var row=rows.getFirst();
        if((Integer)row.get("version")!=version||row.get("revoked_at")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"role_token_version_conflict");
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"role_token_expired");
        return row;
    }
    @Transactional public Map<String,Object> edit(UUID id,int version,String name,String role,Instant expires){
        validate(name,role,expires);editable(id,version);
        db.update("UPDATE role_tokens SET name=?,role=?,expires_at=?,version=version+1 WHERE token_id=?",name,role,Timestamp.from(expires),id);
        return Map.of("tokenId",id,"version",version+1,"name",name,"role",role,"expiresAt",expires);
    }
    @Transactional public Map<String,Object> rotate(UUID id,int version){
        editable(id,version);String token=secret();
        db.update("UPDATE role_tokens SET token_hash=?,version=version+1 WHERE token_id=?",hash(token),id);
        return Map.of("tokenId",id,"version",version+1,"token",token);
    }
    @Transactional public void revoke(UUID id,int version){
        var rows=db.queryForList("SELECT version,revoked_at FROM role_tokens WHERE token_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row=rows.getFirst();int current=(Integer)row.get("version");
        if(row.get("revoked_at")!=null && current==(long)version+1)return;
        if(current!=version||row.get("revoked_at")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"role_token_version_conflict");
        db.update("UPDATE role_tokens SET revoked_at=now(),version=version+1 WHERE token_id=?",id);
    }
    public static boolean permits(String role,String method,String path){
        path=path.replaceAll(";[^/]*","").replaceAll("/+$","");
        if(role.equals("admin"))return true;
        if(path.startsWith("/api/v1/messages/content-export"))return false;
        if(method.equals("GET"))return path.startsWith("/api/v1/")&&!path.startsWith("/api/v1/access")&&!path.startsWith("/api/v1/audit");
        if(method.equals("POST") && (path.equals("/api/v1/session")||path.equals("/api/v1/session/logout")||path.equals("/api/v1/memory/search")||path.equals("/api/v1/templates/preview")||path.matches("/api/v1/automation/rules/[a-f0-9-]{36}/preview")||path.equals("/api/v1/routing/preview")))return true;
        if(!role.equals("operator"))return false;
        return path.equals("/api/v1/messages")&&method.equals("POST") || path.matches("/api/v1/messages/[a-f0-9-]{36}/(read|analysis(?:/retry)?)")&&method.equals("POST") ||
            path.matches("/api/v1/messages/[a-f0-9-]{36}/deliveries/(?:cancel|retry)")&&method.equals("POST") ||
            path.startsWith("/api/v1/deliveries/")&&method.equals("POST") || path.startsWith("/api/v1/automation/") || path.startsWith("/api/v1/routing/") || path.startsWith("/api/v1/templates/");
    }
}
