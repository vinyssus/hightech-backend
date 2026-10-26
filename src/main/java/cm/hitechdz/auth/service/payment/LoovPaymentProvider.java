package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loov (LoovPay) : API marchand https://api.secure.payment.loov-solutions.com/v1
 * (en-têtes app-key / merchant-key ; POST /payment/payin/mobile ; GET /payment/status/{reference}).
 *
 * Loov ne documente pas de signature de webhook : une notification n'est donc jamais crue telle quelle.
 * Elle sert uniquement de déclencheur, et le statut est relu auprès de l'API Loov avec nos clés.
 */
@Component
public class LoovPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(LoovPaymentProvider.class);
    static final String NAME = "loov";

    private static final Set<String> SUCCESS = Set.of("success", "successful", "succeeded", "completed", "complete",
            "paid", "approved", "accepted", "validated");
    private static final Set<String> FAILURE = Set.of("failed", "failure", "error", "rejected", "declined", "refused",
            "insufficient_funds");
    private static final Set<String> CANCELLED = Set.of("cancelled", "canceled", "aborted");
    private static final Set<String> EXPIRED = Set.of("expired", "timeout", "timed_out");

    private final String appKey;
    private final String merchantKey;
    private final String publicBaseUrl;
    private final RestClient client;
    private final ObjectMapper json;

    public LoovPaymentProvider(@Value("${app.payments.loov.app-key:}") String appKey,
                               @Value("${app.payments.loov.merchant-key:}") String merchantKey,
                               @Value("${app.payments.loov.base-url:https://api.secure.payment.loov-solutions.com/v1}") String baseUrl,
                               @Value("${app.public-base-url:}") String publicBaseUrl,
                               ObjectMapper json) {
        this.appKey = appKey == null ? "" : appKey.trim();
        this.merchantKey = merchantKey == null ? "" : merchantKey.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.json = json;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(20_000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts)
                .defaultHeader("app-key", this.appKey)
                .defaultHeader("merchant-key", this.merchantKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return !appKey.isEmpty() && !merchantKey.isEmpty() && !publicBaseUrl.isEmpty();
    }

    @Override
    public InitResult initiate(Payment payment, String customerName, String customerEmail) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", customerName);
        customer.put("email", customerEmail == null || customerEmail.isBlank() ? "client@hitech-dz.cm" : customerEmail);
        customer.put("phoneNumber", payment.getPhone().replace("+", ""));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", payment.getAmount());
        body.put("operator", operatorCode(payment.getOperator()));
        body.put("phoneNumber", payment.getPhone().replace("+", ""));
        body.put("customer", customer);
        body.put("callback_url", publicBaseUrl + "/api/payments/webhooks/" + NAME);
        try {
            String raw = client.post().uri("/payment/payin/mobile").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            JsonNode r = json.readTree(raw == null ? "{}" : raw);
            String reference = text(r, "reference");
            boolean error = r.path("error").asBoolean(false);
            if (error || reference == null) {
                String message = text(r, "message");
                log.warn("Loov a refusé le paiement {} : {}", payment.getId(), message);
                return new InitResult(reference, Payment.Status.FAILED,
                        "Le paiement n'a pas pu être lancé" + (message == null ? "." : " : " + message), null);
            }
            return new InitResult(reference, Payment.Status.PENDING, "Validez le paiement sur votre téléphone.", null);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("Loov : demande refusée ({}) pour le paiement {} : {}", e.getStatusCode().value(),
                        payment.getId(), e.getResponseBodyAsString());
                return new InitResult(null, Payment.Status.FAILED, "Le paiement a été refusé par l'opérateur.", null);
            }
            log.error("Loov indisponible ({}) pour le paiement {}", e.getStatusCode().value(), payment.getId());
            throw new ProviderUnavailableException();
        } catch (RestClientException | java.io.IOException e) {
            log.error("Loov injoignable pour le paiement {} : {}", payment.getId(), e.toString());
            throw new ProviderUnavailableException();
        }
    }

    @Override
    public ProviderStatus fetchStatus(String providerReference) {
        try {
            String raw = client.get().uri("/payment/status/{ref}", providerReference).retrieve().body(String.class);
            JsonNode r = json.readTree(raw == null ? "{}" : raw);
            Long amount = r.hasNonNull("amount") ? r.get("amount").asLong() : null;
            return new ProviderStatus(mapStatus(text(r, "status")), amount, text(r, "currency"), text(r, "message"));
        } catch (RestClientException | java.io.IOException e) {
            log.error("Loov : statut indisponible pour {} : {}", providerReference, e.toString());
            throw new ProviderUnavailableException();
        }
    }

    @Override
    public VerifiedEvent verifyWebhook(Map<String, String> headers, String rawBody) {
        String reference;
        try {
            JsonNode r = json.readTree(rawBody == null ? "{}" : rawBody);
            reference = firstText(r, "reference", "ref", "transaction_ref", "transactionRef", "transaction_id");
            if (reference == null && r.has("data")) {
                reference = firstText(r.get("data"), "reference", "ref", "transaction_id");
            }
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification illisible.");
        }
        if (reference == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Référence de paiement absente.");
        }
        // Statut faisant foi : relu auprès de Loov avec nos clés (la notification n'est qu'un déclencheur)
        ProviderStatus verified = fetchStatus(reference);
        return new VerifiedEvent(reference + ":" + verified.status(), reference, verified.status(),
                verified.amount(), verified.currency(), verified.message());
    }

    static String operatorCode(PaymentMethod operator) {
        return switch (operator) {
            case ORANGE_MONEY -> "orange-money-cm";
            case MTN_MOMO -> "mtn-cm";
            default -> throw new IllegalArgumentException("Opérateur non Mobile Money : " + operator);
        };
    }

    /** Statuts Loov non documentés exhaustivement : tout ce qui n'est pas reconnu reste PENDING. */
    static Payment.Status mapStatus(String status) {
        if (status == null) {
            return Payment.Status.PENDING;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        if (SUCCESS.contains(s)) return Payment.Status.SUCCEEDED;
        if (FAILURE.contains(s)) return Payment.Status.FAILED;
        if (CANCELLED.contains(s)) return Payment.Status.CANCELLED;
        if (EXPIRED.contains(s)) return Payment.Status.EXPIRED;
        return Payment.Status.PENDING;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
