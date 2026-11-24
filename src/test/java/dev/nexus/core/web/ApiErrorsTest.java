package dev.nexus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 400 的错误码决定前端能做什么分支：能不能提示"去解锁主题"、要不要刷新版本。
 * 这里固定"领域原因码原样透出、其余一律折叠"的边界，避免下次重构又把原因吃回去。
 */
class ApiErrorsTest {
    static String error(ResponseEntity<?> response) {
        return ((Map<?,?>)response.getBody()).get("error").toString();
    }

    @Test void domainReasonCodesReachTheCaller() {
        var errors=new ApiErrors();
        for(String code:new String[]{"theme_locked","outfit_not_owned","furnishing_not_owned",
            "invalid_appearance_request_id","invalid_companion_array","appearance_revision_conflict"}) {
            var response=errors.invalidArgument(new IllegalArgumentException(code));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error(response)).isEqualTo(code);
        }
    }

    @Test void implementationDetailsStayFolded() {
        var errors=new ApiErrors();
        for(String message:new String[]{"For input string: \"x\"","Cannot deserialize value of type `int`",
            "duplicate key value violates unique constraint","theme","UPPER_CASE","",null}) {
            var response=errors.invalidArgument(new IllegalArgumentException(message));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error(response)).withFailMessage("message=%s",message).isEqualTo("invalid_request");
        }
    }

    @Test void explicitStatusExceptionKeepsItsReason() {
        var response=new ApiErrors().status(new ResponseStatusException(HttpStatus.CONFLICT,"appearance_result_pending"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(response)).isEqualTo("appearance_result_pending");
    }
}
