package cm.hitechdz.auth.service.assistant;

import cm.hitechdz.auth.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 503 : assistant non configuré, quota atteint ou fournisseur d'IA injoignable. */
public class AssistantUnavailableException extends ApiException {
    public AssistantUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
