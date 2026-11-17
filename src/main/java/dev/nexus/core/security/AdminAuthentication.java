package dev.nexus.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class AdminAuthentication extends OncePerRequestFilter {
    private static final String POLICY = "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TokenCredentials credentials;
    private final MemoryToolAccess memoryTools;
    private final RoleTokens roles;
    private final BrowserSessions sessions;

    public AdminAuthentication(TokenCredentials credentials,MemoryToolAccess memoryTools,RoleTokens roles,BrowserSessions sessions) {
        this.credentials=credentials;
        this.memoryTools=memoryTools;
        this.roles=roles;this.sessions=sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", POLICY);
        String path = request.getServletPath();
        // Only static GETs are public. Authenticate every application endpoint,
        // including URL spellings normalized by the servlet container.
        boolean publicAsset = request.getMethod().equals("GET") &&
            (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico") || path.startsWith("/assets/"));
        if (publicAsset) {
            // Naive UI mounts style elements. Authorize only this document's style
            // nonce, keeping the script policy and all API authentication unchanged.
            if (path.equals("/") || path.equals("/index.html")) {
                var page = new ClassPathResource("static/index.html");
                if (page.exists()) {
                    byte[] random = new byte[24]; RANDOM.nextBytes(random);
                    String nonce = Base64.getEncoder().encodeToString(random);
                    String html;
                    try (var input = page.getInputStream()) { html = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                    if (!html.contains("__NEXUS_STYLE_NONCE__")) throw new ServletException("Console style nonce placeholder is missing");
                    response.setHeader("Content-Security-Policy", POLICY + "; style-src 'self' 'nonce-" + nonce + "'");
                    response.setHeader("Cache-Control", "no-store");
                    response.setContentType("text/html"); response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(html.replace("__NEXUS_STYLE_NONCE__", nonce));
                    return;
                }
            }
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        boolean sourceEndpoint=request.getMethod().equals("POST")&&path.matches("/api/v1/hooks/[a-z0-9][a-z0-9-]{0,23}") ||
            request.getMethod().equals("GET")&&path.matches("/api/v1/hooks/[a-z0-9][a-z0-9-]{0,23}/messages/[a-fA-F0-9-]{36}");
        if(sourceEndpoint) {
            // Authentication and source scope are enforced by WebhookSources; this
            // path never accepts an administrator token as a source credential.
            if(java.util.Collections.list(request.getHeaders("Authorization")).size()!=1 || request.getHeader("Origin")!=null || request.getMethod().equals("POST") && (java.util.Collections.list(request.getHeaders("X-Nexus-Timestamp")).size()!=1 || java.util.Collections.list(request.getHeaders("X-Nexus-Signature")).size()!=1)){response.setStatus(401);return;}
            if(request.getMethod().equals("POST")) {
                byte[] raw=request.getInputStream().readNBytes(32769);
                if(raw.length>32768){response.setStatus(413);return;}
                chain.doFilter(wrapped(request,raw),response);
            }else chain.doFilter(request,response);
            return;
        }
        String supplied = request.getHeader("Authorization");
        boolean memoryEndpoint=path.equals(MemoryToolAccess.PATH);
        if(memoryEndpoint&&!memoryTools.enabled()){response.setStatus(404);return;}
        boolean accepted;
        RoleTokens.Identity identity=null;
        BrowserSessions.Session browser=null;
        int headers=java.util.Collections.list(request.getHeaders("Authorization")).size();
        // Explicit Bearer credentials always take precedence, including invalid ones.
        try {
            if(!memoryEndpoint&&headers==0)browser=sessions.resolve(request);
            accepted=browser!=null?credentials.acceptsAdminFingerprint(browser.fingerprint()):headers==1&&(memoryEndpoint?memoryTools.accepts(supplied):credentials.acceptsAdmin(supplied));
            if(!accepted&&!memoryEndpoint&&(headers==1||browser!=null)){identity=browser!=null?roles.authenticateFingerprint(browser.fingerprint()):roles.authenticate(supplied);accepted=identity!=null;}
            if(!accepted&&!memoryEndpoint&&headers==0&&sessions.hasCookie(request))sessions.invalidate(request,response);
        }
        catch(TokenCredentials.Unavailable|BrowserSessionStore.Unavailable unavailable) {
            response.setStatus(503);response.setHeader("Retry-After","1");response.setContentType("application/json");
            response.getWriter().write("{\"error\":\""+unavailable.getMessage()+"\"}");return;
        }
        if (!accepted) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        if(browser!=null&&!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())&&!sessions.csrf(request,browser)){
            response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"error\":\"csrf_required\"}");return;
        }
        request.setAttribute("nexus.authorization",supplied);request.setAttribute("nexus.browserSession",browser);
        String role=memoryEndpoint?"memory":identity==null?"admin":identity.role();
        request.setAttribute("nexus.role",role);request.setAttribute("nexus.actor",memoryEndpoint?"memory-tools":identity==null?credentials.adminActor():identity.actor());
        if(!memoryEndpoint)response.setHeader("X-Nexus-Role",role);
        if(!memoryEndpoint&&!RoleTokens.permits(role,request.getMethod(),path)) {
            response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"error\":\"forbidden\"}");return;
        }
        // This machine endpoint has no browser clients or CORS allowlist.
        if(memoryEndpoint&&request.getHeader("Origin")!=null){response.setStatus(403);return;}
        if (request.getMethod().equals("POST") || request.getMethod().equals("PUT") || request.getMethod().equals("PATCH")) {
            byte[] bytes = request.getInputStream().readNBytes(32769);
            if (bytes.length > 32768) {
                response.setStatus(413);
                return;
            }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
            }, response);
        } else {
            chain.doFilter(request, response);
        }
    }
    private HttpServletRequest wrapped(HttpServletRequest request,byte[] bytes) {
        return new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };}
        };
    }
}
