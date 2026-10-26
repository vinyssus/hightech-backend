package cm.hitechdz.auth.exception;

import cm.hitechdz.auth.dto.order.CheckoutConflictResponse;
import org.springframework.http.HttpStatus;

/** 409 de commande avec le panier recalculé (prix ou stock changés, devis expiré...). */
public class CheckoutConflictException extends ApiException {

    private final CheckoutConflictResponse body;

    public CheckoutConflictException(CheckoutConflictResponse body) {
        super(HttpStatus.CONFLICT, body.message(), body.fieldErrors());
        this.body = body;
    }

    public CheckoutConflictResponse getBody() {
        return body;
    }
}
