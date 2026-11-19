package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loov API v2 (https://loov-solutions.com/docs) :
 * <ul>
 *   <li>authentification : {@code Authorization: Bearer <clé API>} (une seule clé) ;</li>
 *   <li>paiement Mobile Money : {@code POST /v2/payments} avec {@code Idempotency-Key} ;</li>
 *   <li>statut : {@code GET /v2/payments/{reference}} → pending, processing, succeeded, failed, expired ;</li>
 *   <li>notifications signées : en-tête {@code Loov-Signature: t=<horodatage>,v1=<hex>},
 *       HMAC-SHA256(secret, horodatage + "." + corps brut), refusées au-delà de 5 minutes ;</li>
 *   <li>mode test : {@code POST /v2/test/transactions/{reference}/simulate} {"outcome": "succeeded"|"failed"}.</li>
 * </ul>
 * Sans secret de signature configuré, une notification n'est jamais crue : le statut est relu auprès de Loov.
 */
@Component
public class LoovPaymentProvider implements PaymentProvider, TestSimulation {

    private static final Logger log = LoggerFactory.getLogger(LoovPaymentProvider.class);
    static final String NAME = "loov";
    static final String DEFAULT_BASE_URL = "https://api.secure.payment.loov-solutions.com/v2";
    static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    static final long MINIMUM_XAF = 200;

    private final String apiKey;
    private final String webhookSecret;
    private final String publicBaseUrl;
    private final RestClient client;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public LoovPaymentProvider(@Value("${app.payments.loov.api-key:}") String apiKey,
                               @Value("${app.payments.loov.webhook-secret:}") String webhookSecret,
                               @Value("${app.payments.loov.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                               @Value("${app.public-base-url:}") String publicBaseUrl,
                               ObjectMapper json) {
        this(apiKey, webhookSecret, publicBaseUrl, json, Clock.systemUTC(), builder(baseUrl).build());
    }

    /** Constructeur de test : client HTTP et horloge fournis. */
    LoovPaymentProvider(String apiKey, String webhookSecret, String publicBaseUrl, ObjectMapper json, Clock clock,
                        RestClient client) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.json = json;
        this.clock = clock;
        this.client = client;
        if (configured() && this.webhookSecret.isEmpty()) {
            log.warn("Loov : LOOV_WEBHOOK_SECRET absent, chaque notification sera revérifiée auprès de l'API Loov.");
        }
    }

    static RestClient.Builder builder(String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(20_000);
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
        return RestClient.builder().baseUrl(base).requestFactory(timeouts);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty() && !publicBaseUrl.isEmpty();
    }

    @Override
    public long minimumAmount() {
        return MINIMUM_XAF;
    }

    // ------------------------------------------------------------------ paiement
    @Override
    public InitResult initiate(Payment payment, String customerName, String customerEmail) {
        String phone = payment.getPhone().replace("+", "");
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", customerName);
        customer.put("email", customerEmail);
        customer.put("phone", phone);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", payment.getAmount());
        body.put("currency", payment.getCurrency());
        body.put("operator", operatorCode(payment.getOperator()));
        body.put("phone_number", phone);
        body.put("customer", customer);
        body.put("callback_url", publicBaseUrl + "/api/payments/webhooks/" + NAME);
        body.put("merchant_reference", payment.getId());
        body.put("metadata", Map.of("order", payment.getOrder() == null ? "" : String.valueOf(payment.getOrder().getNumber())));
        try {
            String raw = client.post().uri("/payments")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Idempotency-Key", payment.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            JsonNode r = json.readTree(raw == null ? "{}" : raw);
            String reference = text(r, "reference");
            Payment.Status status = mapStatus(text(r, "status"));
            if (reference == null) {
                log.error("Loov : réponse sans référence pour le paiement {}", payment.getId());
                throw new ProviderUnavailableException();
            }
            boolean live = r.path("livemode").asBoolean(true);
            if (!live) {
                log.warn("Loov : paiement {} créé en MODE TEST (aucun débit réel).", payment.getId());
            }
            if (status == Payment.Status.FAILED) {
                return new InitResult(reference, Payment.Status.FAILED, failureMessage(r), null);
            }
            String message = live ? "Validez le paiement sur votre téléphone."
                    : "Paiement de TEST Loov : aucun débit réel, à terminer par simulation.";
            return new InitResult(reference, Payment.Status.PENDING, message, text(r, "checkout_url"));
        } catch (RestClientResponseException e) {
            return handleInitError(e, payment);
        } catch (RestClientException | java.io.IOException e) {
            log.error("Loov injoignable pour le paiement {} : {}", payment.getId(), e.toString());
            throw new ProviderUnavailableException();
        }
    }

    private InitResult handleInitError(RestClientResponseException e, Payment payment) {
        String code = errorCode(e.getResponseBodyAsString());
        int http = e.getStatusCode().value();
        switch (code) {
            case "validation_failed", "route_not_available", "provider_not_available" -> {
                log.warn("Loov : paiement {} refusé ({}) : {}", payment.getId(), code, e.getResponseBodyAsString());
                String msg = code.equals("validation_failed")
                        ? "Le paiement a été refusé : vérifiez le numéro Mobile Money."
                        : "Ce moyen de paiement est momentanément indisponible. Essayez l'autre opérateur ou le paiement à la livraison.";
                return new InitResult(null, Payment.Status.FAILED, msg, null);
            }
            case "unauthorized", "account_not_active" ->
                    // Problème de configuration du site, pas du client : jamais présenté comme un refus
                    log.error("Loov : {} (HTTP {}). Vérifiez LOOV_API_KEY et l'état du compte marchand Loov.", code, http);
            default -> log.error("Loov : erreur HTTP {} ({}) pour le paiement {}", http, code, payment.getId());
        }
        throw new ProviderUnavailableException();
    }

    @Override
    public ProviderStatus fetchStatus(String providerReference) {
        try {
            String raw = client.get().uri("/payments/{ref}", providerReference)
                    .header("Authorization", "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            JsonNode r = json.readTree(raw == null ? "{}" : raw);
            Payment.Status status = mapStatus(text(r, "status"));
            return new ProviderStatus(status, amount(r), text(r, "currency"),
                    status == Payment.Status.FAILED ? failureMessage(r) : null);
        } catch (RestClientException | java.io.IOException e) {
            log.error("Loov : statut indisponible pour {} : {}", providerReference, e.toString());
            throw new ProviderUnavailableException();
        }
    }

    // ------------------------------------------------------------------ notifications
    @Override
    public VerifiedEvent verifyWebhook(Map<String, String> headers, String rawBody) {
        String body = rawBody == null ? "" : rawBody;
        if (!webhookSecret.isEmpty()) {
            verifySignature(header(headers, "Loov-Signature"), body);
        }
        JsonNode data;
        try {
            JsonNode root = json.readTree(body.isEmpty() ? "{}" : body);
            data = root.has("data") ? root.get("data") : root;
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification illisible.");
        }
        String reference = text(data, "reference");
        if (reference == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Référence de paiement absente.");
        }
        if (webhookSecret.isEmpty()) {
            // Sans signature vérifiable : la notification n'est qu'un déclencheur, le statut est relu chez Loov
            ProviderStatus verified = fetchStatus(reference);
            return new VerifiedEvent(reference + ":" + verified.status(), reference, verified.status(),
                    verified.amount(), verified.currency(), verified.message());
        }
        Payment.Status status = mapStatus(text(data, "status"));
        return new VerifiedEvent(reference + ":" + status, reference, status, amount(data), text(data, "currency"),
                status == Payment.Status.FAILED ? failureMessage(data) : null);
    }

    /** Loov-Signature: t=<horodatage>,v1=<hex> ; HMAC-SHA256(secret, t + "." + corps). 401 sinon. */
    void verifySignature(String header, String body) {
        if (header == null || header.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature absente.");
        }
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) timestamp = kv[1].trim();
            if (kv[0].equals("v1")) signatures.add(kv[1].trim().toLowerCase(Locale.ROOT));
        }
        long t;
        try {
            t = Long.parseLong(timestamp == null ? "" : timestamp);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature invalide.");
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - t) > SIGNATURE_TOLERANCE_SECONDS) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Notification expirée.");
        }
        byte[] expected = hmac(timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String s : signatures) {
            match |= MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII));
        }
        if (!match) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature invalide.");
        }
    }

    String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ mode test
    @Override
    public void simulate(String providerReference, String outcome) {
        try {
            client.post().uri("/test/transactions/{ref}/simulate", providerReference)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("outcome", outcome))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("Loov : simulation refusée ({}) pour {} : {}", e.getStatusCode().value(), providerReference,
                    e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.CONFLICT,
                    "Loov a refusé la simulation : elle n'est possible qu'avec une clé de test et un paiement en attente.");
        } catch (RestClientException e) {
            throw new ProviderUnavailableException();
        }
    }

    // ------------------------------------------------------------------ helpers
    static String operatorCode(PaymentMethod operator) {
        return switch (operator) {
            case ORANGE_MONEY -> "orange-money-cm";
            case MTN_MOMO -> "mtn-cm";
            default -> throw new IllegalArgumentException("Opérateur non Mobile Money : " + operator);
        };
    }

    /** Statuts v2 documentés ; tout autre valeur reste en attente (jamais une réussite par défaut). */
    static Payment.Status mapStatus(String status) {
        if (status == null) {
            return Payment.Status.PENDING;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "succeeded" -> Payment.Status.SUCCEEDED;
            case "failed" -> Payment.Status.FAILED;
            case "expired" -> Payment.Status.EXPIRED;
            default -> Payment.Status.PENDING;   // pending, processing, inconnu
        };
    }

    private static String failureMessage(JsonNode r) {
        JsonNode f = r.path("failure");
        String message = text(f, "message");
        return "Le paiement n'a pas abouti" + (message == null || message.isBlank() ? "." : " : " + message);
    }

    private String errorCode(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body).path("error").path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static Long amount(JsonNode r) {
        return r.hasNonNull("amount") && r.get("amount").canConvertToLong() ? r.get("amount").asLong() : null;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
