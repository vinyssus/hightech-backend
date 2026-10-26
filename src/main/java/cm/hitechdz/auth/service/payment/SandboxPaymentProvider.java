package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.util.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prestataire de TEST, à activer explicitement (PAYMENT_PROVIDER=sandbox + PAYMENT_SANDBOX_SECRET).
 * Aucun argent ne circule : le paiement reste PENDING jusqu'à une notification signée
 * (HMAC-SHA256 du corps avec le secret, en-têtes X-Sandbox-Timestamp et X-Sandbox-Signature),
 * envoyée par vos tests sur /api/payments/webhooks/sandbox. Ne jamais l'activer en production.
 */
@Component
public class SandboxPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentProvider.class);
    static final String NAME = "sandbox";
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final String secret;
    private final ObjectMapper json;
    private final Map<String, ProviderStatus> statuses = new ConcurrentHashMap<>();

    public SandboxPaymentProvider(@Value("${app.payments.sandbox.secret:}") String secret, ObjectMapper json) {
        this.secret = secret == null ? "" : secret.trim();
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return secret.length() >= 16;
    }

    @Override
    public InitResult initiate(Payment payment, String customerName, String customerEmail) {
        String reference = "SBX-" + Tokens.reference("", 12);
        statuses.put(reference, new ProviderStatus(Payment.Status.PENDING, payment.getAmount(), payment.getCurrency(), null));
        log.warn("SANDBOX : paiement simulé {} ({} {}) — aucun débit réel", reference, payment.getAmount(), payment.getCurrency());
        return new InitResult(reference, Payment.Status.PENDING,
                "Mode test : aucun débit réel. Validation simulée par notification signée.", null);
    }

    @Override
    public ProviderStatus fetchStatus(String providerReference) {
        ProviderStatus s = statuses.get(providerReference);
        return s != null ? s : new ProviderStatus(Payment.Status.PENDING, null, null, null);
    }

    @Override
    public VerifiedEvent verifyWebhook(Map<String, String> headers, String rawBody) {
        String timestamp = header(headers, "x-sandbox-timestamp");
        String signature = header(headers, "x-sandbox-signature");
        if (!configured() || timestamp == null || signature == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature absente.");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Horodatage invalide.");
        }
        if (Duration.between(Instant.ofEpochSecond(ts), Instant.now()).abs().compareTo(MAX_SKEW) > 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Notification périmée.");
        }
        if (!Tokens.constantTimeEquals(sign(timestamp + "." + rawBody), signature.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature invalide.");
        }
        try {
            JsonNode r = json.readTree(rawBody);
            String eventId = r.path("eventId").asText(null);
            String reference = r.path("reference").asText(null);
            String status = r.path("status").asText("");
            if (eventId == null || reference == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "eventId et reference sont obligatoires.");
            }
            Payment.Status mapped = switch (status.toUpperCase(Locale.ROOT)) {
                case "SUCCEEDED" -> Payment.Status.SUCCEEDED;
                case "FAILED" -> Payment.Status.FAILED;
                case "EXPIRED" -> Payment.Status.EXPIRED;
                case "CANCELLED" -> Payment.Status.CANCELLED;
                default -> Payment.Status.PENDING;
            };
            Long amount = r.hasNonNull("amount") ? r.get("amount").asLong() : null;
            String currency = r.path("currency").asText(null);
            statuses.put(reference, new ProviderStatus(mapped, amount, currency, null));
            return new VerifiedEvent(eventId, reference, mapped, amount, currency, r.path("message").asText(null));
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification illisible.");
        }
    }

    /** HMAC-SHA256 hexadécimal de « timestamp.corps » (utilisé aussi par les tests). */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
