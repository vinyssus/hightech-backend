package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Le prestataire ne répond pas ou renvoie une erreur technique : 503, réessayer plus tard. */
public class ProviderUnavailableException extends ApiException {

    public ProviderUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Le service de paiement est momentanément indisponible. Réessayez dans un instant.");
    }
}
