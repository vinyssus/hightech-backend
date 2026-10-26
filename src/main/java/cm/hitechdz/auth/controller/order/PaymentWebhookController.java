package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.service.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notifications des prestataires (sans JWT). Authentification et anti-rejeu dans PaymentService ;
 * 200 pour une notification traitée ou ignorée (doublon), 401 si elle n'est pas authentique.
 */
@RestController
public class PaymentWebhookController {

    private final PaymentService payments;

    public PaymentWebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/payments/webhooks/{provider}")
    public Map<String, Object> receive(@PathVariable String provider,
                                       @RequestBody(required = false) String body,
                                       HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        boolean applied = payments.webhook(provider, headers, body == null ? "" : body);
        return Map.of("received", true, "applied", applied);
    }
}
