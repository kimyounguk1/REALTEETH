package realTeeth.backendDeveloper.global.error;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum ErrorCode {

    API_KEY_PROCESS_FAIL(HttpStatus.BAD_REQUEST),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    AI_PROCESS_FAIL(HttpStatus.BAD_REQUEST),
    NON_EXIST_TASK(HttpStatus.BAD_REQUEST),
    DUPLICATE_REQUEST(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;
}
