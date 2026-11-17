package dev.nexus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BrowserSessionsTest {
    static final String BEARER="Bearer isolated-unit-fixture-only-not-a-real-credential";
    static class Time extends Clock {
        Instant now=Instant.parse("2026-11-17T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId ignored){return this;}
        @Override public Instant instant(){return now;}
    }
    static MockHttpServletRequest request(MockHttpServletResponse response){
        var request=new MockHttpServletRequest();String header=response.getHeader("Set-Cookie");
        request.setCookies(new Cookie(BrowserSessions.COOKIE,header.split(";",2)[0].split("=",2)[1]));return request;
    }
    @Test void sessionsHaveAbsoluteLifetimeSecureCookiesAndNoCredentialInMetadata(){
        var time=new Time();var sessions=new BrowserSessions(time);var secure=new MockHttpServletRequest();secure.setSecure(true);var response=new MockHttpServletResponse();
        var session=sessions.create(secure,response,BEARER);var request=request(response);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);assertThat(response.getHeader("Set-Cookie")).contains("Secure","HttpOnly","SameSite=Strict").doesNotContain(BEARER);
        assertThat(sessions.resolve(request)).isSameAs(session);assertThat(session.describe().toString()).doesNotContain(BEARER);
        time.now=time.now.plusSeconds(28800);assertThat(sessions.resolve(request)).isNull();assertThat(sessions.active(session)).isFalse();
    }
    @Test void externalHostsRequireSecureCookiesEvenBehindAnHttpProxy(){
        var sessions=new BrowserSessions(new Time());var external=new MockHttpServletRequest();external.setServerName("console.example.test");var response=new MockHttpServletResponse();
        sessions.create(external,response,BEARER);assertThat(response.getHeader("Set-Cookie")).contains("Secure");
        var local=new MockHttpServletResponse();sessions.create(new MockHttpServletRequest(),local,BEARER);assertThat(local.getHeader("Set-Cookie")).doesNotContain("Secure;");
    }
    @Test void replacingAndLoggingOutInvalidatesTheOldSessionAndCsrfProof(){
        var sessions=new BrowserSessions(new Time());var firstResponse=new MockHttpServletResponse();var first=sessions.create(new MockHttpServletRequest(),firstResponse,BEARER);var original=request(firstResponse);
        original.addHeader("X-Nexus-CSRF",first.describe().get("csrfToken"));assertThat(sessions.csrf(original,first)).isTrue();
        original.addHeader("X-Nexus-CSRF",first.describe().get("csrfToken"));assertThat(sessions.csrf(original,first)).isFalse();
        var replacementResponse=new MockHttpServletResponse();var replacement=sessions.create(original,replacementResponse,BEARER);
        assertThat(sessions.active(first)).isFalse();assertThat(sessions.resolve(original)).isNull();
        var next=request(replacementResponse);assertThat(sessions.resolve(next)).isSameAs(replacement);assertThat(replacement.describe().get("csrfToken")).isNotEqualTo(first.describe().get("csrfToken"));
        var logoutResponse=new MockHttpServletResponse();sessions.invalidate(next,logoutResponse);assertThat(sessions.resolve(next)).isNull();assertThat(logoutResponse.getHeader("Set-Cookie")).contains("Max-Age=0");
    }
}
