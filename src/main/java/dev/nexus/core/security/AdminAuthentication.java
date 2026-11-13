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
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthentication extends OncePerRequestFilter {
    private final byte[] expected;
    private final MemoryToolAccess memoryTools;

    public AdminAuthentication(@Value("${nexus.admin-token}") String token,MemoryToolAccess memoryTools) {
        if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalStateException("NEXUS_ADMIN_TOKEN must contain at least 32 non-whitespace characters");
        expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.memoryTools=memoryTools;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        String path = request.getServletPath();
        // Only static GETs are public. Authenticate every application endpoint,
        // including URL spellings normalized by the servlet container.
        boolean publicAsset = request.getMethod().equals("GET") &&
            (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico") || path.startsWith("/assets/"));
        if (publicAsset) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        String supplied = request.getHeader("Authorization");
        boolean memoryEndpoint=path.equals(MemoryToolAccess.PATH);
        if(memoryEndpoint&&!memoryTools.enabled()){response.setStatus(404);return;}
        if (memoryEndpoint ? !memoryTools.accepts(supplied)||java.util.Collections.list(request.getHeaders("Authorization")).size()!=1 :
                supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
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
}
