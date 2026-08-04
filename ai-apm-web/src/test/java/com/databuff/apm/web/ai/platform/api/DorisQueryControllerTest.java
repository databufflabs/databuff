package com.databuff.apm.web.ai.platform.api;

import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import com.databuff.apm.web.ai.platform.AiPlatformExceptionHandler;
import com.databuff.apm.web.storage.DorisReadQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DorisQueryControllerTest {

    @Test
    void queryReturnsServiceResult() {
        DorisReadQueryService service = mock(DorisReadQueryService.class);
        when(service.query(eq("SELECT 1"), eq(10))).thenReturn(
                new DorisReadQueryService.QueryResult(
                        List.of("1"), List.of(List.of(1)), 1, "SELECT 1", "databuff"));
        DorisQueryController controller = new DorisQueryController(service);

        Map<String, Object> body = controller.query(new DorisQueryController.QueryRequest("SELECT 1", 10));

        assertThat(body.get("rowCount")).isEqualTo(1);
        assertThat(body.get("columns")).isEqualTo(List.of("1"));
    }

    @Test
    void blankSqlIsBadRequest() {
        DorisQueryController controller = new DorisQueryController(mock(DorisReadQueryService.class));
        assertThatThrownBy(() -> controller.query(new DorisQueryController.QueryRequest("  ", null)))
                .isInstanceOf(AiPlatformApiException.class)
                .extracting(ex -> ((AiPlatformApiException) ex).httpStatus())
                .isEqualTo(400);
    }

    @Test
    void unavailableMapsTo503() {
        DorisReadQueryService service = mock(DorisReadQueryService.class);
        when(service.query(any(), any())).thenThrow(new IllegalStateException("Doris unavailable"));
        DorisQueryController controller = new DorisQueryController(service);
        AiPlatformExceptionHandler handler = new AiPlatformExceptionHandler();

        try {
            controller.query(new DorisQueryController.QueryRequest("SELECT 1", null));
        } catch (AiPlatformApiException ex) {
            ResponseEntity<Map<String, Object>> response = handler.handleApiException(ex);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).containsEntry("error", "doris_unavailable");
            return;
        }
        throw new AssertionError("expected AiPlatformApiException");
    }
}
