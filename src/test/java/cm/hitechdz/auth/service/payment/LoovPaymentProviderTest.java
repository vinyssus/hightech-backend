package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Loov API v2 : requêtes envoyées, statuts, erreurs, signatures des notifications, mode test. */
class LoovPaymentProviderTest {

    static final String BASE = "https://api.secure.payment.loov-solutions.com/v2";
    static final String SECRET = "whsec_test_secret";
    static final Instant NOW = Instant.parse("2026-11-19T22:00:00Z");

    ObjectMapper json = new ObjectMapper();
    MockRestServiceServer server;
    LoovPaymentProvider loov;

    @BeforeEach
    void setUp() {
        loov = provider(SECRET);
    }

    LoovPaymentProvider provider(String secret) {
        RestClient.Builder b = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(b).build();
        return new LoovPaymentProvider("loov_test_abc", secret, "https://hightech-backend.onrender.com/", json,
                Clock.fixed(NOW, ZoneOffset.UTC), b.build());
    }

    static Payment payment(PaymentMethod operator) {
        CustomerOrder o = new CustomerOrder();
        o.setNumber("HD-7K2M9QXA");
        Payment p = new Payment();
        p.setId("pay_abc123");
        p.setOrder(o);
        p.setOperator(operator);
        p.setPhone("+237650000000");
        p.setAmount(5000);
        p.setCurrency("XAF");
        return p;
    }

    String signed(String body, long t) {
        return "t=" + t + ",v1=" + loov.hmac(t + "." + body);
    }

    @Test
    void configuration_and_constants() {
        assertThat(loov.configured()).isTrue();
        assertThat(loov.minimumAmount()).isEqualTo(200);
        assertThat(new LoovPaymentProvider("", "", "https://x.cm", json, Clock.systemUTC(), RestClient.create()).configured()).isFalse();
        assertThat(new LoovPaymentProvider("k", "", "", json, Clock.systemUTC(), RestClient.create()).configured()).isFalse();
        assertThat(LoovPaymentProvider.operatorCode(PaymentMethod.ORANGE_MONEY)).isEqualTo("orange-money-cm");
        assertThat(LoovPaymentProvider.operatorCode(PaymentMethod.MTN_MOMO)).isEqualTo("mtn-cm");
        assertThat(LoovPaymentProvider.mapStatus("succeeded")).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(LoovPaymentProvider.mapStatus("failed")).isEqualTo(Payment.Status.FAILED);
        assertThat(LoovPaymentProvider.mapStatus("expired")).isEqualTo(Payment.Status.EXPIRED);
        assertThat(LoovPaymentProvider.mapStatus("processing")).isEqualTo(Payment.Status.PENDING);
        assertThat(LoovPaymentProvider.mapStatus("pending")).isEqualTo(Payment.Status.PENDING);
        assertThat(LoovPaymentProvider.mapStatus("paid")).isEqualTo(Payment.Status.PENDING);   // inconnu : jamais une réussite
        assertThat(LoovPaymentProvider.mapStatus(null)).isEqualTo(Payment.Status.PENDING);
    }

    @Test
    void initiate_sends_v2_request() {
        server.expect(requestTo(BASE + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer loov_test_abc"))
                .andExpect(header("Idempotency-Key", "pay_abc123"))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.currency").value("XAF"))
                .andExpect(jsonPath("$.operator").value("mtn-cm"))
                .andExpect(jsonPath("$.phone_number").value("237650000000"))
                .andExpect(jsonPath("$.customer.name").value("Awa Diop"))
                .andExpect(jsonPath("$.customer.email").value("awa@example.com"))
                .andExpect(jsonPath("$.merchant_reference").value("pay_abc123"))
                .andExpect(jsonPath("$.metadata.order").value("HD-7K2M9QXA"))
                .andExpect(jsonPath("$.callback_url").value("https://hightech-backend.onrender.com/api/payments/webhooks/loov"))
                .andRespond(withSuccess("""
                        {"reference":"PAYHBN2MJVPY32K7MSZXXN9","type":"payin","status":"processing","livemode":true,
                         "currency":"XAF","amount":5000,"fee":150,"checkout_url":null,"failure":null}""", MediaType.APPLICATION_JSON));
        PaymentProvider.InitResult r = loov.initiate(payment(PaymentMethod.MTN_MOMO), "Awa Diop", "awa@example.com");
        assertThat(r.providerReference()).isEqualTo("PAYHBN2MJVPY32K7MSZXXN9");
        assertThat(r.status()).isEqualTo(Payment.Status.PENDING);
        assertThat(r.message()).isEqualTo("Validez le paiement sur votre téléphone.");
        server.verify();
    }

    @Test
    void initiate_in_test_mode_is_flagged() {
        server.expect(requestTo(BASE + "/payments")).andExpect(jsonPath("$.operator").value("orange-money-cm"))
                .andRespond(withSuccess("{\"reference\":\"PAYTEST1\",\"status\":\"processing\",\"livemode\":false}",
                        MediaType.APPLICATION_JSON));
        PaymentProvider.InitResult r = loov.initiate(payment(PaymentMethod.ORANGE_MONEY), "Awa", "awa@example.com");
        assertThat(r.status()).isEqualTo(Payment.Status.PENDING);
        assertThat(r.message()).contains("TEST");
    }

    @Test
    void initiate_errors() {
        server.expect(requestTo(BASE + "/payments")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"validation_failed\",\"message\":\"Invalid\",\"details\":{\"fields\":{\"phone_number\":[\"invalid\"]}}}}"));
        PaymentProvider.InitResult refused = loov.initiate(payment(PaymentMethod.MTN_MOMO), "Awa", "awa@example.com");
        assertThat(refused.status()).isEqualTo(Payment.Status.FAILED);
        assertThat(refused.message()).contains("numéro Mobile Money");

        loov = provider(SECRET);
        server.expect(requestTo(BASE + "/payments")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"route_not_available\",\"message\":\"x\"}}"));
        assertThat(loov.initiate(payment(PaymentMethod.MTN_MOMO), "Awa", "a@b.cm").status()).isEqualTo(Payment.Status.FAILED);

        loov = provider(SECRET);
        server.expect(requestTo(BASE + "/payments")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"unauthorized\",\"message\":\"bad key\"}}"));
        assertThatThrownBy(() -> loov.initiate(payment(PaymentMethod.MTN_MOMO), "Awa", "a@b.cm"))
                .isInstanceOf(ProviderUnavailableException.class);

        loov = provider(SECRET);
        server.expect(requestTo(BASE + "/payments")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> loov.initiate(payment(PaymentMethod.MTN_MOMO), "Awa", "a@b.cm"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void fetch_status() {
        server.expect(requestTo(BASE + "/payments/PAY1")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer loov_test_abc"))
                .andRespond(withSuccess("{\"reference\":\"PAY1\",\"status\":\"succeeded\",\"amount\":5000,\"currency\":\"XAF\"}",
                        MediaType.APPLICATION_JSON));
        PaymentProvider.ProviderStatus s = loov.fetchStatus("PAY1");
        assertThat(s.status()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(s.amount()).isEqualTo(5000L);
        assertThat(s.currency()).isEqualTo("XAF");

        loov = provider(SECRET);
        server.expect(requestTo(BASE + "/payments/PAY2")).andRespond(withSuccess(
                "{\"reference\":\"PAY2\",\"status\":\"failed\",\"amount\":5000,\"currency\":\"XAF\","
                        + "\"failure\":{\"code\":\"provider_failed\",\"message\":\"Solde insuffisant\"}}", MediaType.APPLICATION_JSON));
        PaymentProvider.ProviderStatus f = loov.fetchStatus("PAY2");
        assertThat(f.status()).isEqualTo(Payment.Status.FAILED);
        assertThat(f.message()).isEqualTo("Le paiement n'a pas abouti : Solde insuffisant");
    }

    @Test
    void signed_webhook_is_trusted_and_checked() {
        String body = "{\"event\":\"payin.succeeded\",\"livemode\":true,\"data\":{\"reference\":\"PAY1\",\"type\":\"payin\","
                + "\"status\":\"succeeded\",\"currency\":\"XAF\",\"amount\":5000,\"merchant_reference\":\"pay_abc123\"}}";
        long t = NOW.getEpochSecond();
        PaymentProvider.VerifiedEvent e = loov.verifyWebhook(Map.of("loov-signature", signed(body, t)), body);
        assertThat(e.providerReference()).isEqualTo("PAY1");
        assertThat(e.status()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(e.amount()).isEqualTo(5000L);
        assertThat(e.currency()).isEqualTo("XAF");
        assertThat(e.eventKey()).isEqualTo("PAY1:SUCCEEDED");
        server.verify();   // aucune requête : notification signée

        assertThatThrownBy(() -> loov.verifyWebhook(Map.of("Loov-Signature", signed(body, t) + "00"), body))
                .isInstanceOf(ApiException.class).hasMessage("Signature invalide.");
        assertThatThrownBy(() -> loov.verifyWebhook(Map.of("Loov-Signature", signed(body, t)), body.replace("5000", "50")))
                .isInstanceOf(ApiException.class).hasMessage("Signature invalide.");
        assertThatThrownBy(() -> loov.verifyWebhook(Map.of("Loov-Signature", signed(body, t - 301)), body))
                .isInstanceOf(ApiException.class).hasMessage("Notification expirée.");
        assertThatThrownBy(() -> loov.verifyWebhook(Map.of("Loov-Signature", signed(body, t + 301)), body))
                .isInstanceOf(ApiException.class).hasMessage("Notification expirée.");
        assertThatThrownBy(() -> loov.verifyWebhook(Map.of(), body))
                .isInstanceOf(ApiException.class).hasMessage("Signature absente.");
        assertThat(((ApiException) org.assertj.core.api.Assertions.catchThrowable(() ->
                loov.verifyWebhook(Map.of(), body))).getStatus().value()).isEqualTo(401);

        String failed = "{\"event\":\"payin.failed\",\"data\":{\"reference\":\"PAY3\",\"status\":\"failed\",\"amount\":5000,"
                + "\"currency\":\"XAF\",\"failure\":{\"code\":\"provider_failed\",\"message\":\"Refusé\",\"reason\":\"x\"}}}";
        PaymentProvider.VerifiedEvent fe = loov.verifyWebhook(Map.of("Loov-Signature", signed(failed, t)), failed);
        assertThat(fe.status()).isEqualTo(Payment.Status.FAILED);
        assertThat(fe.message()).contains("Refusé");
    }

    @Test
    void without_secret_the_status_is_fetched_from_loov() {
        loov = provider("");
        server.expect(requestTo(BASE + "/payments/PAY9")).andRespond(withSuccess(
                "{\"reference\":\"PAY9\",\"status\":\"expired\",\"amount\":5000,\"currency\":\"XAF\"}", MediaType.APPLICATION_JSON));
        String forged = "{\"event\":\"payin.succeeded\",\"data\":{\"reference\":\"PAY9\",\"status\":\"succeeded\",\"amount\":5000}}";
        PaymentProvider.VerifiedEvent e = loov.verifyWebhook(Map.of(), forged);
        assertThat(e.status()).isEqualTo(Payment.Status.EXPIRED);   // le contenu non signé n'est pas cru
        server.verify();
    }

    @Test
    void simulate_in_test_mode() {
        server.expect(requestTo(BASE + "/test/transactions/PAYTEST1/simulate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer loov_test_abc"))
                .andExpect(jsonPath("$.outcome").value("succeeded"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        loov.simulate("PAYTEST1", "succeeded");
        server.verify();

        loov = provider(SECRET);
        server.expect(requestTo(BASE + "/test/transactions/PAYLIVE/simulate")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"forbidden\"}}"));
        assertThatThrownBy(() -> loov.simulate("PAYLIVE", "succeeded"))
                .isInstanceOf(ApiException.class).hasMessageContaining("clé de test");
    }
}
