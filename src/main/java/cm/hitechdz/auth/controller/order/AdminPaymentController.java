package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.dto.order.PaymentStatusResponse;
import cm.hitechdz.auth.service.payment.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Paiements (admin)")
public class AdminPaymentController {

    private final PaymentService payments;

    public AdminPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Mode test Loov : termine un paiement en attente, {"outcome": "succeeded" | "failed"}. Refusé avec une clé de production. */
    @PostMapping("/{paymentId}/simulate")
    @Operation(summary = "Simuler le résultat d'un paiement de test (clé Loov de test uniquement)")
    public PaymentStatusResponse simulate(@PathVariable String paymentId, @RequestBody(required = false) Map<String, String> body) {
        return payments.simulateTest(paymentId, body == null ? null : body.get("outcome"));
    }
}
