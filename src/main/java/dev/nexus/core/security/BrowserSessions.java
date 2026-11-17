package dev.nexus.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/** Durable console sessions bind a random Cookie to the original credential's digest. */
@Service
public class BrowserSessions {
    static final String COOKIE="nexus_console_session";
    private static final int SECONDS=28800;
    private static final SecureRandom RANDOM=new SecureRandom();
    public record Session(String idHash,String fingerprint,String csrf,Instant expiresAt){
        public Map<String,Object> describe(){return Map.of("persisted",true,"csrfToken",csrf,"expiresAt",expiresAt);}
        @Override public String toString(){return "BrowserSession[redacted]";}
    }
    private final BrowserSessionStore store;
    private final Clock clock;
    @Autowired public BrowserSessions(BrowserSessionStore store){this(store,Clock.systemUTC());}
    BrowserSessions(BrowserSessionStore store,Clock clock){this.store=store;this.clock=clock;}
    private static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String cookie(HttpServletRequest request){
        String value=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if(COOKIE.equals(cookie.getName())){if(value!=null)return "";value=cookie.getValue();}
        return value;
    }
    private String key(HttpServletRequest request){String value=cookie(request);return value!=null&&value.matches("[A-Za-z0-9_-]{43}")?TokenCredentials.digest(value.getBytes(StandardCharsets.UTF_8)):null;}
    private boolean secure(HttpServletRequest request){
        // A forwarded HTTPS hint only tightens Secure, never relaxes it or grants identity.
        return request.isSecure()||"https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))||!java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(request.getServerName().toLowerCase(java.util.Locale.ROOT));
    }
    public boolean hasCookie(HttpServletRequest request){return cookie(request)!=null;}
    public Session resolve(HttpServletRequest request){
        String key=key(request);if(key==null)return null;var session=store.read(key);
        if(session!=null&&!session.expiresAt().isAfter(clock.instant())){store.remove(key);return null;}return session;
    }
    public boolean active(Session session){return session!=null&&session.expiresAt().isAfter(clock.instant())&&session.equals(store.read(session.idHash()));}
    public boolean csrf(HttpServletRequest request,Session session){
        var values=Collections.list(request.getHeaders("X-Nexus-CSRF"));return values.size()==1&&MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8),values.getFirst().getBytes(StandardCharsets.UTF_8));
    }
    public Session create(HttpServletRequest request,HttpServletResponse response,String authorization){return createFingerprint(request,response,TokenCredentials.credentialFingerprint(authorization));}
    public Session renew(HttpServletRequest request,HttpServletResponse response,Session current){return createFingerprint(request,response,current.fingerprint());}
    private Session createFingerprint(HttpServletRequest request,HttpServletResponse response,String fingerprint){
        String id=random();var session=new Session(TokenCredentials.digest(id.getBytes(StandardCharsets.UTF_8)),fingerprint,random(),clock.instant().plusSeconds(SECONDS));
        store.replace(key(request),session,clock.instant());
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,id).path("/api/v1").httpOnly(true).secure(secure(request)).sameSite("Strict").maxAge(SECONDS).build().toString());return session;
    }
    public void invalidate(HttpServletRequest request,HttpServletResponse response){
        store.remove(key(request));
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,"").path("/api/v1").httpOnly(true).secure(secure(request)).sameSite("Strict").maxAge(0).build().toString());
    }
}
