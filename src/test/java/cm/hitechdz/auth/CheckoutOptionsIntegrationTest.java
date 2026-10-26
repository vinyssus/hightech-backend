package cm.hitechdz.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuration de production réelle : aucun prestataire en ligne (PAYMENT_PROVIDER absent),
 * zones gérées par l'admin. Vérifie que GET /api/checkout/options reflète immédiatement l'admin.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkoutoptions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000",
        // un secret sandbox seul ne doit PAS activer le paiement simulé
        "app.payments.provider=sandbox",
        "app.payments.sandbox.secret=secret-sandbox-de-test-0123456789"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CheckoutOptionsIntegrationTest {

    private static final String VERCEL = "https://hitech-dz-commerce-suite-kappa.vercel.app";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static String admin, customer, productId;
    private static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.2." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode options() throws Exception {
        return body(mvc.perform(get("/api/checkout/options")).andExpect(status().isOk()));
    }

    private String quote(String zone, String city) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("items", List.of(Map.of("productId", productId, "qty", 1)));
        b.put("zoneId", zone);
        b.put("city", city);
        b.put("promoCode", null);
        return body(send(post("/api/checkout/quote"), null, b).andExpect(status().isOk())).get("quoteId").asText();
    }

    private Map<String, Object> orderBody(String quoteId, String zone, String city, String method) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Client Test");
        d.put("phone", "699123456");
        d.put("email", "client@example.cm");
        d.put("zoneId", zone);
        d.put("city", city);
        d.put("address", "Akwa, rue des Écoles");
        d.put("notes", "");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quoteId", quoteId);
        b.put("items", List.of(Map.of("productId", productId, "qty", 1)));
        b.put("promoCode", null);
        b.put("delivery", d);
        b.put("paymentMethod", method);
        return b;
    }

    @Test @Order(1)
    void no_active_zone_gives_empty_options_like_production() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Awa", "lastName", "Test",
                "email", "awa@example.cm", "phone", "6 70 00 00 02", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Options")).andExpect(status().isCreated());
        productId = body(send(post("/api/admin/products"), admin, Map.of("name", "Souris", "category", "options",
                "brand", "HP", "price", 5_000, "stock", 20, "status", "published")).andExpect(status().isCreated()))
                .get("id").asText();

        // Zones livrées inactives par la migration : c'est l'état constaté en production
        JsonNode o = options();
        assertThat(o.get("currency").asText()).isEqualTo("XAF");
        assertThat(o.get("freeShippingFrom").isNull()).isTrue();
        assertThat(o.get("shippingZones").size()).isZero();
        assertThat(o.get("paymentMethods").size()).isZero();
        // Le paiement en ligne n'est jamais annoncé sans intégration réelle (sandbox non autorisé)
        mvc.perform(get("/api/checkout/options")).andExpect(jsonPath("$.paymentMethods").isArray());
    }

    @Test @Order(2)
    void admin_endpoints_stay_protected() throws Exception {
        send(patch("/api/admin/shipping-zones/douala"), null, Map.of("active", true)).andExpect(status().isUnauthorized());
        send(patch("/api/admin/shipping-zones/douala"), customer, Map.of("active", true)).andExpect(status().isForbidden());
        send(post("/api/admin/shipping-zones"), customer, Map.of("label", "X", "fee", 1)).andExpect(status().isForbidden());
        send(put("/api/admin/checkout/settings"), customer, Map.of("quoteValidityMinutes", 15)).andExpect(status().isForbidden());
        assertThat(options().get("shippingZones").size()).isZero();
    }

    @Test @Order(3)
    void activating_a_zone_is_saved_and_immediately_visible() throws Exception {
        // Activation seule : libellé et tarif enregistrés par l'admin sont conservés
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.label").value("Douala"))
                .andExpect(jsonPath("$.fee").value(2000));
        send(get("/api/admin/shipping-zones"), admin, null).andExpect(jsonPath("$[?(@.id=='douala')].active").value(true));

        JsonNode o = options();
        assertThat(o.get("shippingZones").size()).isEqualTo(1);
        JsonNode z = o.get("shippingZones").get(0);
        Set<String> fields = new java.util.HashSet<>();
        z.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "label", "delay", "fee", "codAllowed", "requiresCity", "pickup");
        assertThat(z.get("id").asText()).isEqualTo("douala");
        assertThat(z.get("delay").isNull()).isTrue();            // aucun délai inventé
        assertThat(z.get("codAllowed").asBoolean()).isTrue();
        // Tableau de chaînes, uniquement le paiement à la livraison
        assertThat(o.get("paymentMethods").toString()).isEqualTo("[\"CASH_ON_DELIVERY\"]");
        assertThat(o.get("freeShippingFrom").isNull()).isTrue();

        // Zones inactives absentes
        assertThat(o.get("shippingZones").toString()).doesNotContain("yaounde", "retrait", "autre");
    }

    @Test @Order(4)
    void zone_created_by_admin_is_visible_with_its_own_rules() throws Exception {
        send(post("/api/admin/shipping-zones"), admin, Map.of("label", "Bafoussam")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fee").value("Les frais sont obligatoires (0 pour gratuit)."));
        send(post("/api/admin/shipping-zones"), admin, Map.of("id", "bafoussam", "label", "Bafoussam", "fee", 4000,
                "delay", "48 à 72 h", "codAllowed", false, "active", true, "position", 5))
                .andExpect(status().isCreated());
        JsonNode o = options();
        assertThat(o.get("shippingZones").size()).isEqualTo(2);
        JsonNode baf = o.get("shippingZones").get(1);
        assertThat(baf.get("id").asText()).isEqualTo("bafoussam");
        assertThat(baf.get("codAllowed").asBoolean()).isFalse();
        assertThat(baf.get("delay").asText()).isEqualTo("48 à 72 h");

        // Seule une zone sans paiement à la livraison est active → aucun moyen annoncé
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", false)).andExpect(status().isOk());
        assertThat(options().get("paymentMethods").size()).isZero();
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", true)).andExpect(status().isOk());
        assertThat(options().get("paymentMethods").toString()).isEqualTo("[\"CASH_ON_DELIVERY\"]");
    }

    @Test @Order(5)
    void settings_cannot_invent_free_shipping_by_mistake() throws Exception {
        send(put("/api/admin/checkout/settings"), admin, Map.of("freeShippingFrom", 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.freeShippingFrom").exists());
        send(get("/api/admin/checkout/settings"), admin, null).andExpect(jsonPath("$.freeShippingFrom").doesNotExist());
        assertThat(options().get("freeShippingFrom").isNull()).isTrue();
    }

    @Test @Order(6)
    void cash_on_delivery_order_in_allowed_zone_and_idempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> b = orderBody(quote("douala", null), "douala", null, "CASH_ON_DELIVERY");
        JsonNode first = body(send(post("/api/orders").header("Idempotency-Key", key), null, b)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.shipping").value(2000))
                .andExpect(jsonPath("$.total").value(7000)));
        String number = first.get("number").asText();

        JsonNode again = body(send(post("/api/orders").header("Idempotency-Key", key), null, b).andExpect(status().isCreated()));
        assertThat(again.get("number").asText()).isEqualTo(number);
        send(get("/api/admin/orders").param("q", number), admin, null).andExpect(jsonPath("$.totalElements").value(1));
        send(get("/api/admin/orders"), admin, null).andExpect(jsonPath("$.totalElements").value(1));

        // L'invité retrouve sa commande avec son jeton, pas sans
        mvc.perform(get("/api/orders/" + number).header("X-Guest-Order-Token", again.get("guestAccessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("UNPAID"));
        mvc.perform(get("/api/orders/" + number)).andExpect(status().isUnauthorized());
    }

    @Test @Order(7)
    void cash_on_delivery_refused_where_not_allowed_and_online_refused_without_provider() throws Exception {
        send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), null,
                orderBody(quote("bafoussam", null), "bafoussam", null, "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod")
                        .value("Le paiement à la livraison n'est pas disponible pour cette zone."));
        send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), null,
                orderBody(quote("douala", null), "douala", null, "ORANGE_MONEY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod", containsString("pas encore disponible")));
        // Zone inactive refusée
        send(post("/api/checkout/quote"), null, Map.of("items", List.of(Map.of("productId", productId, "qty", 1)),
                "zoneId", "yaounde")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.zoneId").value("Cette zone de livraison n'est pas disponible."));
        send(get("/api/admin/orders"), admin, null).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test @Order(8)
    void cors_preflight_keeps_required_headers() throws Exception {
        mvc.perform(options("/api/checkout/options").header("Origin", VERCEL)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", VERCEL));
        mvc.perform(options("/api/admin/shipping-zones/douala").header("Origin", VERCEL)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
        mvc.perform(options("/api/orders").header("Origin", VERCEL)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key,x-guest-order-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("idempotency-key")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-guest-order-token")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("authorization")));
        mvc.perform(options("/api/orders").header("Origin", "https://site-pirate.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
