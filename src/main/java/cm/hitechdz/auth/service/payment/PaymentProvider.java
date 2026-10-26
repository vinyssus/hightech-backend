package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.order.Payment;

import java.util.Map;

/**
 * Prestataire de paiement Mobile Money. Une réussite n'est JAMAIS déduite d'un retour navigateur :
 * uniquement d'une réponse du prestataire (API de statut ou notification authentifiée).
 */
public interface PaymentProvider {

    /** Identifiant utilisé dans l'URL du webhook : /api/payments/webhooks/{name}. */
    String name();

    boolean configured();

    /** Demande de débit : le client valide sur son téléphone (le PIN ne transite jamais par le site). */
    InitResult initiate(Payment payment, String customerName, String customerEmail);

    /** Statut faisant foi, interrogé auprès du prestataire. */
    ProviderStatus fetchStatus(String providerReference);

    /**
     * Authentifie une notification et renvoie l'événement vérifié.
     * @throws cm.hitechdz.auth.exception.ApiException 401 si la notification n'est pas authentique
     */
    VerifiedEvent verifyWebhook(Map<String, String> headers, String rawBody);

    record InitResult(String providerReference, Payment.Status status, String message, String checkoutUrl) {}

    record ProviderStatus(Payment.Status status, Long amount, String currency, String message) {}

    /** eventKey sert à l'anti-rejeu (unique par prestataire). */
    record VerifiedEvent(String eventKey, String providerReference, Payment.Status status, Long amount,
                         String currency, String message) {}
}
