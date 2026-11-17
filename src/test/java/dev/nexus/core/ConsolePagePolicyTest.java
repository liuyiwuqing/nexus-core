package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import dev.nexus.core.security.AdminAuthentication;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsolePagePolicyTest {
    private MockHttpServletResponse get(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path); request.setServletPath(path);
        var response = new MockHttpServletResponse();
        new AdminAuthentication(null, null, null, null).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test void consoleAuthorizesOnlyItsOwnStyleNonceAndPreventsCaching() throws Exception {
        Assumptions.assumeTrue(new ClassPathResource("static/index.html").exists(),
            "Build the frontend before checking the actual console HTML");
        var first = get("/"); var second = get("/index.html");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getContentType()).startsWith("text/html");
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        String nonce = first.getContentAsString().split("name=\"nexus-style-nonce\" content=\"")[1].split("\"")[0];
        assertThat(nonce).matches("[A-Za-z0-9+/]{32}");
        assertThat(first.getContentAsString()).doesNotContain("__NEXUS_STYLE_NONCE__");
        assertThat(second.getContentAsString()).doesNotContain(nonce);
        assertThat(first.getHeader("Content-Security-Policy")).isEqualTo(
            "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; style-src 'self' 'nonce-" + nonce + "'");
    }

    @Test void staticAssetsKeepTheOriginalPolicy() throws Exception {
        var response = get("/assets/example.js");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(
            "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
