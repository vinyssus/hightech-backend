package cm.hitechdz.auth.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Erreur métier traduite en réponse JSON { message, fieldErrors }. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus getStatus() { return status; }

    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
