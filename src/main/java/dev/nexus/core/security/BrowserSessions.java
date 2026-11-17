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
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived console sessions; the original Bearer never leaves server memory. */
@Service
public class BrowserSessions {
    static final String COOKIE="nexus_console_session";
    private static final int SECONDS=28800,MAXIMUM=1024;
    private static final SecureRandom RANDOM=new SecureRandom();
    public static final class Session {
        private final String id,authorization,csrf;
        private final Instant expiresAt;
        private Session(String id,String authorization,String csrf,Instant expiresAt){this.id=id;this.authorization=authorization;this.csrf=csrf;this.expiresAt=expiresAt;}
        public String authorization(){return authorization;}
        public Map<String,Object> describe(){return Map.of("persisted",true,"csrfToken",csrf,"expiresAt",expiresAt);}
    }
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final Clock clock;
    @Autowired public BrowserSessions(){this(Clock.systemUTC());}
    BrowserSessions(Clock clock){this.clock=clock;}
    private static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String cookie(HttpServletRequest request){
        String value=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if(COOKIE.equals(cookie.getName())){if(value!=null)return "";value=cookie.getValue();}
        return value;
    }
    private boolean secure(HttpServletRequest request){
        // HTTPS may terminate at a proxy. Never relax Secure for an external host.
        return request.isSecure()||!java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(request.getServerName().toLowerCase(java.util.Locale.ROOT));
    }
    public boolean hasCookie(HttpServletRequest request){return cookie(request)!=null;}
    public Session resolve(HttpServletRequest request){String id=cookie(request);if(id==null)return null;var session=sessions.get(id);if(session!=null&&!active(session)){sessions.remove(id,session);return null;}return session;}
    public boolean active(Session session){return session!=null&&session.expiresAt.isAfter(clock.instant())&&sessions.get(session.id)==session;}
    public boolean csrf(HttpServletRequest request,Session session){
        var values=Collections.list(request.getHeaders("X-Nexus-CSRF"));return values.size()==1&&MessageDigest.isEqual(session.csrf.getBytes(StandardCharsets.UTF_8),values.getFirst().getBytes(StandardCharsets.UTF_8));
    }
    public synchronized Session create(HttpServletRequest request,HttpServletResponse response,String authorization){
        sessions.values().removeIf(s->!s.expiresAt.isAfter(clock.instant()));
        String previous=cookie(request);
        if(sessions.size()-(previous!=null&&sessions.containsKey(previous)?1:0)>=MAXIMUM)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"session_capacity_reached");
        if(previous!=null)sessions.remove(previous);
        var session=new Session(random(),authorization,random(),clock.instant().plusSeconds(SECONDS));sessions.put(session.id,session);
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,session.id).path("/api/v1").httpOnly(true).secure(secure(request)).sameSite("Strict").maxAge(SECONDS).build().toString());
        return session;
    }
    public void invalidate(HttpServletRequest request,HttpServletResponse response){
        String id=cookie(request);if(id!=null)sessions.remove(id);
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,"").path("/api/v1").httpOnly(true).secure(secure(request)).sameSite("Strict").maxAge(0).build().toString());
    }
}
