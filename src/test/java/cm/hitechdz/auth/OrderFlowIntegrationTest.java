package cm.hitechdz.auth;

import cm.hitechdz.auth.service.payment.PaymentService;
import cm.hitechdz.auth.service.payment.SandboxPaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours complet panier → devis → commande → paiement → suivi → administration
 * (H2 mode PostgreSQL, migrations Flyway réelles, prestataire sandbox signé HMAC).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000",
        "app.payments.provider=sandbox",
        "app.payments.sandbox.secret=secret-sandbox-de-test-0123456789",
        "app.payments.sandbox.allowed=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SandboxPaymentProvider sandbox;
    @Autowired PaymentService paymentService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    static String admin, customerA, customerB;
    static String p1, p1Slug, p2, p3, p3Slug;
    static String guestOrder, guestToken, orderA;
    static String paidOrder, paymentId;

    private static final AtomicInteger IP = new AtomicInteger(1);

    // ------------------------------------------------------------------ helpers
    private static RequestPostProcessor newIp() {
        int n = IP.incrementAndGet();
        return r -> { r.setRemoteAddr("10.1." + (n / 250) + "." + (n % 250)); return r; };
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b.with(newIp()));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String login(String email, String password) throws Exception {
        return body(send(post("/api/auth/login"), null, Map.of("email", email, "password", password))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private String register(String email, String first) throws Exception {
        return body(send(post("/api/auth/register"), null, Map.of("firstName", first, "lastName", "Test",
                "email", email, "phone", "6 70 00 00 01", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
    }

    private static Map<String, Object> item(String productId, String sku, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("sku", sku);
        m.put("qty", qty);
        return m;
    }

    private JsonNode quote(String token, List<Map<String, Object>> items, String zone, String city, String promo) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("items", items);
        b.put("zoneId", zone);
        b.put("city", city);
        b.put("promoCode", promo);
        return body(send(post("/api/checkout/quote"), token, b).andExpect(status().isOk()));
    }

    private static Map<String, Object> delivery(String zone, String city, String address) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Client Test");
        d.put("phone", "+237 699 12 34 56");
        d.put("email", "client@example.cm");
        d.put("zoneId", zone);
        d.put("city", city);
        d.put("address", address);
        d.put("notes", "");
        return d;
    }

    private static Map<String, Object> orderBody(String quoteId, List<Map<String, Object>> items, String promo,
                                                 Map<String, Object> delivery, String method) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quoteId", quoteId);
        b.put("items", items);
        b.put("promoCode", promo);
        b.put("delivery", delivery);
        b.put("paymentMethod", method);
        return b;
    }

    private ResultActions order(String token, String key, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/orders");
        if (key != null) b.header("Idempotency-Key", key);
        return send(b, token, body);
    }

    /** Devis + commande en une fois. */
    private JsonNode placeOrder(String token, List<Map<String, Object>> items, String method, String promo) throws Exception {
        JsonNode q = quote(token, items, "douala", null, promo);
        return body(order(token, UUID.randomUUID().toString(),
                orderBody(q.get("quoteId").asText(), items, promo, delivery("douala", null, "Akwa, face pharmacie"), method))
                .andExpect(status().isCreated()));
    }

    private int stockOf(String slug) throws Exception {
        return body(mvc.perform(get("/api/products/" + slug))).get("stock").asInt();
    }

    private int soldOf(String slug) throws Exception {
        return body(mvc.perform(get("/api/products/" + slug))).get("sold").asInt();
    }

    private ResultActions webhook(String body, boolean validSignature) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = validSignature ? sandbox.sign(ts + "." + body) : "0".repeat(64);
        return mvc.perform(post("/api/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON)
                .header("X-Sandbox-Timestamp", ts).header("X-Sandbox-Signature", sig).content(body));
    }

    private String providerRef(String number) throws Exception {
        JsonNode o = body(send(get("/api/admin/orders/" + number), admin, null).andExpect(status().isOk()));
        JsonNode payments = o.get("payments");
        return payments.get(payments.size() - 1).get("providerReference").asText();
    }

    private void sql(String jpql, Object... params) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            var q = em.createQuery(jpql);
            for (int i = 0; i < params.length; i += 2) q.setParameter((String) params[i], params[i + 1]);
            q.executeUpdate();
        });
    }

    // ------------------------------------------------------------------ 1. configuration
    @Test @Order(1)
    void setup_catalog_zones_and_options() throws Exception {
        admin = login("admin@hitech-dz.cm", "Admin123");
        customerA = register("client.a@example.cm", "Alice");
        customerB = register("client.b@example.cm", "Bob");

        send(post("/api/admin/categories"), admin, Map.of("name", "Test Commandes")).andExpect(status().isCreated());
        JsonNode a = body(send(post("/api/admin/products"), admin, Map.of("name", "Produit A", "category", "test-commandes",
                "brand", "HP", "price", 10_000, "stock", 50, "status", "published")).andExpect(status().isCreated()));
        p1 = a.get("id").asText(); p1Slug = a.get("slug").asText();
        p2 = body(send(post("/api/admin/products"), admin, Map.of("name", "Derniere piece", "category", "test-commandes",
                "brand", "HP", "price", 20_000, "stock", 1, "status", "published")).andExpect(status().isCreated()))
                .get("id").asText();
        JsonNode v = body(send(post("/api/admin/products"), admin, Map.of("name", "Telephone V", "category", "test-commandes",
                "brand", "Tecno", "price", 100_000, "stock", 0, "status", "published")).andExpect(status().isCreated()));
        p3 = v.get("id").asText(); p3Slug = v.get("slug").asText();
        send(put("/api/admin/products/" + p3 + "/variants"), admin, Map.of("combinations", List.of(
                Map.of("sku", "V-128", "capacity", "128 Go", "priceDelta", 0, "stock", 5),
                Map.of("sku", "V-256", "capacity", "256 Go", "priceDelta", 20_000, "stock", 2))))
                .andExpect(status().isOk());

        // Zones créées inactives : rien n'est proposé tant que l'admin ne les a pas validées
        mvc.perform(get("/api/checkout/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XAF"))
                .andExpect(jsonPath("$.shippingZones.length()").value(0));
        send(get("/api/admin/shipping-zones"), admin, null)
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].active").value(false));
        send(get("/api/admin/shipping-zones"), customerA, null).andExpect(status().isForbidden());

        send(put("/api/admin/shipping-zones/douala"), admin, Map.of("label", "Douala", "fee", 2000,
                "delay", "24 à 48 h", "active", true)).andExpect(status().isOk());
        send(put("/api/admin/shipping-zones/autre"), admin, Map.of("label", "Autres villes", "fee", 5000,
                "active", true, "codAllowed", false)).andExpect(status().isOk());
        send(put("/api/admin/shipping-zones/retrait"), admin, Map.of("label", "Retrait en boutique", "fee", 0,
                "active", true)).andExpect(status().isOk());

        mvc.perform(get("/api/checkout/options"))
                .andExpect(jsonPath("$.shippingZones.length()").value(3))
                .andExpect(jsonPath("$.shippingZones[0].id").value("douala"))
                .andExpect(jsonPath("$.shippingZones[0].fee").value(2000))
                .andExpect(jsonPath("$.shippingZones[0].delay").value("24 à 48 h"))
                .andExpect(jsonPath("$.shippingZones[1].codAllowed").value(false))
                .andExpect(jsonPath("$.shippingZones[1].requiresCity").value(true))
                .andExpect(jsonPath("$.freeShippingFrom").doesNotExist())
                .andExpect(jsonPath("$.paymentMethods", hasItems("CASH_ON_DELIVERY", "ORANGE_MONEY", "MTN_MOMO")));
    }

    // ------------------------------------------------------------------ 2. panier
    @Test @Order(2)
    void cart_validate_guest_and_logged_in() throws Exception {
        List<Map<String, Object>> items = List.of(item(p1, null, 2), item(p1, null, 2), item(p1, "SKU-INCONNU", 1),
                item(p3, null, 1), item(p3, "V-999", 1), item(p3, "V-256", 3));
        for (String token : new String[]{null, customerA}) {
            send(post("/api/cart/validate"), token, Map.of("items", items))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(5))          // doublons fusionnés
                    .andExpect(jsonPath("$.items[0].qty").value(4))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(10_000))
                    .andExpect(jsonPath("$.items[1].available").value(false))
                    .andExpect(jsonPath("$.items[1].message").value("Cette option n'existe pas pour ce produit"))
                    .andExpect(jsonPath("$.items[2].message").value("Veuillez choisir une option"))
                    .andExpect(jsonPath("$.items[3].available").value(false))
                    .andExpect(jsonPath("$.items[4].qty").value(2))
                    .andExpect(jsonPath("$.items[4].unitPrice").value(120_000))
                    .andExpect(jsonPath("$.items[4].message").value("Plus que 2 en stock"))
                    .andExpect(jsonPath("$.subtotal").value(4 * 10_000 + 2 * 120_000));
        }
        send(post("/api/cart/validate"), null, Map.of("items", List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.subtotal").value(0));
    }

    // ------------------------------------------------------------------ 3. devis
    @Test @Order(3)
    void quotes_shipping_issues_and_zone_rules() throws Exception {
        JsonNode q = quote(null, List.of(item(p1, null, 2)), "douala", null, null);
        assertThat(q.get("quoteId").asText()).startsWith("qt_");
        assertThat(q.get("subtotal").asLong()).isEqualTo(20_000);
        assertThat(q.get("shipping").asLong()).isEqualTo(2_000);
        assertThat(q.get("total").asLong()).isEqualTo(22_000);
        assertThat(q.get("canOrder").asBoolean()).isTrue();
        assertThat(q.get("issues").size()).isZero();

        JsonNode retrait = quote(null, List.of(item(p1, null, 2)), "retrait", null, null);
        assertThat(retrait.get("shipping").asLong()).isZero();

        Map<String, Object> changedPrice = item(p1, null, 1);
        changedPrice.put("price", 9_000);
        JsonNode issues = quote(null, List.of(changedPrice, item(p1, "X", 1), item(p2, null, 5)), "douala", null, null);
        assertThat(issues.get("canOrder").asBoolean()).isFalse();
        assertThat(issues.get("issues").toString()).contains("PRICE_CHANGED", "INVALID_VARIANT", "QUANTITY_EXCEEDED");

        Map<String, Object> b = new LinkedHashMap<>(Map.of("items", List.of(item(p1, null, 1)), "zoneId", "yaounde"));
        send(post("/api/checkout/quote"), null, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.zoneId").exists());
        b.put("zoneId", "autre");
        send(post("/api/checkout/quote"), null, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.city").value("Indiquez votre ville."));
        b.put("city", "Bafoussam");
        send(post("/api/checkout/quote"), null, b).andExpect(status().isOk()).andExpect(jsonPath("$.shipping").value(5000));
    }

    // ------------------------------------------------------------------ 4. promos
    @Test @Order(4)
    void promo_codes_rules() throws Exception {
        send(post("/api/admin/promos"), admin, Map.of("code", "promo10", "type", "PERCENT", "value", 10,
                "active", true, "minSubtotal", 15_000, "maxDiscount", 5_000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PROMO10"));
        send(post("/api/admin/promos"), admin, Map.of("code", "VIEUX", "type", "FIXED", "value", 1000, "active", true,
                "endsAt", Instant.now().minusSeconds(3600).toString())).andExpect(status().isCreated());
        send(post("/api/admin/promos"), admin, Map.of("code", "FREESHIP", "type", "FREE_SHIPPING", "active", true,
                "maxUsesPerCustomer", 1)).andExpect(status().isCreated());
        send(post("/api/admin/promos"), admin, Map.of("code", "PROMO10", "type", "PERCENT", "value", 5))
                .andExpect(status().isConflict());
        send(post("/api/admin/promos"), customerA, Map.of("code", "PIRATE", "type", "FIXED", "value", 5))
                .andExpect(status().isForbidden());

        JsonNode q = quote(null, List.of(item(p1, null, 2)), "douala", null, "promo10");
        assertThat(q.get("discount").asLong()).isEqualTo(2_000);
        assertThat(q.get("total").asLong()).isEqualTo(20_000);
        assertThat(q.get("promo").get("code").asText()).isEqualTo("PROMO10");

        Map<String, Object> b = new LinkedHashMap<>(Map.of("items", List.of(item(p1, null, 1)), "zoneId", "douala", "promoCode", "PROMO10"));
        send(post("/api/checkout/quote"), null, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.promoCode").value(containsString("à partir de")));
        b.put("promoCode", "VIEUX");
        send(post("/api/checkout/quote"), null, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ce code promo a expiré."));
        b.put("promoCode", "BIENVENUE10");   // code de démonstration du frontend : n'existe pas
        send(post("/api/checkout/quote"), null, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.promoCode").value("Code promo invalide."));
        b.put("promoCode", "FREESHIP");
        send(post("/api/checkout/quote"), null, b).andExpect(status().isOk()).andExpect(jsonPath("$.shipping").value(0));
    }

    // ------------------------------------------------------------------ 5. erreurs par champ
    @Test @Order(5)
    void order_field_errors_and_idempotency_key_required() throws Exception {
        JsonNode q = quote(null, List.of(item(p1, null, 1)), "douala", null, null);
        Map<String, Object> d = delivery("douala", null, "");
        d.put("phone", "12345");
        d.put("name", "");
        order(null, UUID.randomUUID().toString(), orderBody(q.get("quoteId").asText(), List.of(item(p1, null, 1)), null, d, "COD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['delivery.name']").exists());
        d.put("name", "Client");
        order(null, UUID.randomUUID().toString(), orderBody(q.get("quoteId").asText(), List.of(item(p1, null, 1)), null, d, "BITCOIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['delivery.phone']").exists())
                .andExpect(jsonPath("$.fieldErrors['delivery.address']").exists())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").exists());

        Map<String, Object> autre = delivery("autre", "Bafoussam", "Marché A");
        JsonNode qa = quote(null, List.of(item(p1, null, 1)), "autre", "Bafoussam", null);
        order(null, UUID.randomUUID().toString(), orderBody(qa.get("quoteId").asText(), List.of(item(p1, null, 1)), null, autre, "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value("Le paiement à la livraison n'est pas disponible pour cette zone."));

        order(null, null, orderBody(q.get("quoteId").asText(), List.of(item(p1, null, 1)), null,
                delivery("douala", null, "Akwa"), "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
    }

    // ------------------------------------------------------------------ 6. commande invitée à la livraison + idempotence
    @Test @Order(6)
    void guest_cash_on_delivery_order_without_operator_and_idempotent_replay() throws Exception {
        int before = stockOf(p1Slug);
        List<Map<String, Object>> items = List.of(item(p1, null, 2));
        JsonNode q = quote(null, items, "douala", null, null);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = orderBody(q.get("quoteId").asText(), items, null,
                delivery("douala", null, "Akwa, face pharmacie"), "CASH_ON_DELIVERY");

        JsonNode o = body(order(null, key, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.currency").value("XAF"))
                .andExpect(jsonPath("$.subtotal").value(20_000))
                .andExpect(jsonPath("$.shipping").value(2_000))
                .andExpect(jsonPath("$.total").value(22_000))
                .andExpect(jsonPath("$.items[0].lineTotal").value(20_000))
                .andExpect(jsonPath("$.delivery.phone").value("+237699123456"))
                .andExpect(jsonPath("$.timeline[0].label").value("Commande enregistrée"))
                .andExpect(jsonPath("$.guestAccessToken").isNotEmpty()));
        guestOrder = o.get("number").asText();
        String firstToken = o.get("guestAccessToken").asText();
        assertThat(guestOrder).matches("HD-[2-9A-Z]{8}");
        assertThat(stockOf(p1Slug)).isEqualTo(before - 2);

        // Aucun appel à l'opérateur ni paiement enregistré
        send(get("/api/admin/orders/" + guestOrder), admin, null).andExpect(jsonPath("$.payments.length()").value(0))
                .andExpect(jsonPath("$.guest").value(true));

        // Reprise après timeout : même clé, même contenu → même commande, nouveau jeton, pas de doublon
        JsonNode replay = body(order(null, key, body).andExpect(status().isCreated()));
        assertThat(replay.get("number").asText()).isEqualTo(guestOrder);
        guestToken = replay.get("guestAccessToken").asText();
        assertThat(guestToken).isNotEqualTo(firstToken);
        assertThat(stockOf(p1Slug)).isEqualTo(before - 2);
        send(get("/api/admin/orders").param("q", guestOrder), admin, null).andExpect(jsonPath("$.totalElements").value(1));

        // Même clé, contenu différent → 409
        Map<String, Object> other = new LinkedHashMap<>(body);
        other.put("items", List.of(item(p1, null, 3)));
        order(null, key, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("clé d'idempotence")));
    }

    // ------------------------------------------------------------------ 7. accès
    @Test @Order(7)
    void guest_order_access_rules() throws Exception {
        mvc.perform(get("/api/orders/" + guestOrder)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/" + guestOrder).header("X-Guest-Order-Token", "jeton-invalide"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + guestOrder).header("X-Guest-Order-Token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(guestOrder))
                .andExpect(jsonPath("$.guestAccessToken").doesNotExist());
        send(get("/api/orders/" + guestOrder), customerA, null).andExpect(status().isForbidden());
        send(get("/api/orders/" + guestOrder), admin, null).andExpect(status().isOk());
        send(get("/api/orders/HD-INCONNU1"), admin, null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 8. client connecté + promo limitée
    @Test @Order(8)
    void logged_in_order_with_promo_limited_per_customer() throws Exception {
        JsonNode o = placeOrder(customerA, List.of(item(p1, null, 1)), "CASH_ON_DELIVERY", "FREESHIP");
        orderA = o.get("number").asText();
        assertThat(o.get("shipping").asLong()).isZero();
        assertThat(o.get("promoCode").asText()).isEqualTo("FREESHIP");
        assertThat(o.has("guestAccessToken")).isFalse();

        Map<String, Object> b = new LinkedHashMap<>(Map.of("items", List.of(item(p1, null, 1)), "zoneId", "douala", "promoCode", "FREESHIP"));
        send(post("/api/checkout/quote"), customerA, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Vous avez déjà utilisé ce code promo."));
        send(post("/api/checkout/quote"), customerB, b).andExpect(status().isOk());

        send(get("/api/me/orders"), customerA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].number").value(orderA));
        send(get("/api/me/orders"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/orders/" + orderA), customerB, null).andExpect(status().isForbidden());
        send(get("/api/orders/" + orderA), customerA, null).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 9. admin + encaissement unique
    @Test @Order(9)
    void admin_transitions_and_single_collection() throws Exception {
        send(get("/api/admin/orders"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/orders"), customerA, null).andExpect(status().isForbidden());
        send(get("/api/admin/orders").param("paymentMethod", "CASH_ON_DELIVERY").param("status", "PENDING_CONFIRMATION"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));

        int soldBefore = soldOf(p1Slug);
        send(post("/api/admin/orders/" + orderA + "/collect-payment").header("Idempotency-Key", "collect-0001"), admin,
                Map.of("method", "CASH")).andExpect(status().isConflict());   // pas encore livrée
        send(patch("/api/admin/orders/" + orderA + "/status"), admin, Map.of("status", "SHIPPED")).andExpect(status().isConflict());
        for (String s : List.of("CONFIRMED", "PREPARING", "SHIPPED", "DELIVERED")) {
            send(patch("/api/admin/orders/" + orderA + "/status"), admin, Map.of("status", s, "note", "ok")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(s))
                    .andExpect(jsonPath("$.paymentStatus").value("UNPAID"));   // logistique ≠ encaissement
        }
        send(patch("/api/admin/orders/" + orderA + "/status"), admin, Map.of("status", "SHIPPED")).andExpect(status().isConflict());

        send(post("/api/admin/orders/" + orderA + "/collect-payment").header("Idempotency-Key", "collect-0001"), admin,
                Map.of("method", "CASH", "reference", "Reçu 42")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.collection.method").value("CASH"))
                .andExpect(jsonPath("$.collection.amount").value(10_000));
        send(post("/api/admin/orders/" + orderA + "/collect-payment").header("Idempotency-Key", "collect-0001"), admin,
                Map.of("method", "CASH")).andExpect(status().isOk());
        send(post("/api/admin/orders/" + orderA + "/collect-payment").header("Idempotency-Key", "collect-0002"), admin,
                Map.of("method", "CASH")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cette commande est déjà encaissée."));
        assertThat(soldOf(p1Slug)).isEqualTo(soldBefore + 1);

        send(get("/api/orders/" + orderA), customerA, null)
                .andExpect(jsonPath("$.timeline[?(@.label=='Paiement encaissé')]").exists())
                .andExpect(jsonPath("$.timeline[?(@.status=='DELIVERED')]").exists());
    }

    // ------------------------------------------------------------------ 10. devis expiré / panier différent
    @Test @Order(10)
    void expired_or_mismatched_quote_is_refused() throws Exception {
        List<Map<String, Object>> items = List.of(item(p1, null, 1));
        JsonNode q = quote(null, items, "douala", null, null);
        sql("update CheckoutQuote q set q.expiresAt = :t where q.id = :id", "t", Instant.now().minusSeconds(5), "id", q.get("quoteId").asText());
        order(null, UUID.randomUUID().toString(), orderBody(q.get("quoteId").asText(), items, null, delivery("douala", null, "Akwa"), "CASH_ON_DELIVERY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(10_000));

        JsonNode q2 = quote(null, items, "douala", null, null);
        order(null, UUID.randomUUID().toString(), orderBody(q2.get("quoteId").asText(), List.of(item(p1, null, 2)), null,
                delivery("douala", null, "Akwa"), "CASH_ON_DELIVERY"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_MISMATCH"));
    }

    // ------------------------------------------------------------------ 11. prix changé entre devis et commande
    @Test @Order(11)
    void price_change_after_quote_gives_409_without_reserving() throws Exception {
        List<Map<String, Object>> items = List.of(item(p1, null, 1));
        JsonNode q = quote(null, items, "douala", null, null);
        int stock = stockOf(p1Slug);
        send(put("/api/admin/products/" + p1), admin, Map.of("name", "Produit A", "category", "test-commandes",
                "brand", "HP", "price", 11_000, "stock", stock, "status", "published")).andExpect(status().isOk());
        order(null, UUID.randomUUID().toString(), orderBody(q.get("quoteId").asText(), items, null, delivery("douala", null, "Akwa"), "CASH_ON_DELIVERY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(11_000))
                .andExpect(jsonPath("$.message").value(containsString("ont changé")));
        assertThat(stockOf(p1Slug)).isEqualTo(stock);
        send(put("/api/admin/products/" + p1), admin, Map.of("name", "Produit A", "category", "test-commandes",
                "brand", "HP", "price", 10_000, "stock", stock, "status", "published")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 12. concurrence dernière unité
    @Test @Order(12)
    void last_unit_sold_once_under_concurrency() throws Exception {
        List<Map<String, Object>> items = List.of(item(p2, null, 1));
        String q1 = quote(null, items, "douala", null, null).get("quoteId").asText();
        String q2 = quote(null, items, "douala", null, null).get("quoteId").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String qid : List.of(q1, q2)) {
            calls.add(() -> {
                go.await();
                return order(null, UUID.randomUUID().toString(), orderBody(qid, items, null, delivery("douala", null, "Akwa"), "CASH_ON_DELIVERY"))
                        .andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> results = new ArrayList<>();
        for (Callable<Integer> c : calls) results.add(pool.submit(c));
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    }

    // ------------------------------------------------------------------ 13. paiement en ligne + webhooks
    @Test @Order(13)
    void online_payment_pending_then_verified_webhook_and_duplicates() throws Exception {
        int soldBefore = soldOf(p1Slug);
        JsonNode o = placeOrder(null, List.of(item(p1, null, 3)), "ORANGE_MONEY", null);
        paidOrder = o.get("number").asText();
        String token = o.get("guestAccessToken").asText();
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("paymentStatus").asText()).isEqualTo("PENDING");
        assertThat(o.get("reservationExpiresAt").asText()).isNotEmpty();

        MockHttpServletRequestBuilder pay = post("/api/orders/" + paidOrder + "/payments").header("X-Guest-Order-Token", token);
        send(pay, null, Map.of("operator", "ORANGE_MONEY", "phone", "699123456")).andExpect(status().isBadRequest());   // clé absente
        send(post("/api/orders/" + paidOrder + "/payments").header("X-Guest-Order-Token", token).header("Idempotency-Key", "pay-key-0001"),
                null, Map.of("operator", "ORANGE_MONEY", "phone", "12")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
        JsonNode p = body(send(post("/api/orders/" + paidOrder + "/payments").header("X-Guest-Order-Token", token)
                        .header("Idempotency-Key", "pay-key-0001"), null, Map.of("operator", "ORANGE_MONEY", "phone", "699123456"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(32_000))
                .andExpect(jsonPath("$.currency").value("XAF"))
                .andExpect(jsonPath("$.operator").value("ORANGE_MONEY")));
        paymentId = p.get("paymentId").asText();

        // Même clé → même paiement ; autre clé pendant un paiement en cours → 409
        send(post("/api/orders/" + paidOrder + "/payments").header("X-Guest-Order-Token", token).header("Idempotency-Key", "pay-key-0001"),
                null, Map.of("operator", "ORANGE_MONEY", "phone", "699123456"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.paymentId").value(paymentId));
        send(post("/api/orders/" + paidOrder + "/payments").header("X-Guest-Order-Token", token).header("Idempotency-Key", "pay-key-0002"),
                null, Map.of("operator", "MTN_MOMO", "phone", "677123456"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/orders/" + paidOrder + "/payments/" + paymentId).header("X-Guest-Order-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().string("Retry-After", "5"));
        mvc.perform(get("/api/orders/" + paidOrder + "/payments/" + paymentId)).andExpect(status().isUnauthorized());

        String ref = providerRef(paidOrder);
        String event = json.writeValueAsString(Map.of("eventId", "evt-1", "reference", ref, "status", "SUCCEEDED",
                "amount", 32_000, "currency", "XAF"));
        webhook(event, false).andExpect(status().isUnauthorized());
        send(get("/api/orders/" + paidOrder), admin, null).andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        webhook(event, true).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        mvc.perform(get("/api/orders/" + paidOrder).header("X-Guest-Order-Token", token))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.timeline[?(@.label=='Paiement confirmé')]").exists());
        assertThat(soldOf(p1Slug)).isEqualTo(soldBefore + 3);

        // Double notification (même événement) puis notification tardive : sans effet
        webhook(event, true).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        webhook(json.writeValueAsString(Map.of("eventId", "evt-2", "reference", ref, "status", "SUCCEEDED",
                "amount", 32_000, "currency", "XAF")), true).andExpect(status().isOk());
        assertThat(soldOf(p1Slug)).isEqualTo(soldBefore + 3);
        mvc.perform(get("/api/orders/" + paidOrder + "/payments/" + paymentId).header("X-Guest-Order-Token", token))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(header().doesNotExist("Retry-After"));

        // Montant incohérent : jamais marqué payé
        JsonNode o2 = placeOrder(null, List.of(item(p1, null, 1)), "MTN_MOMO", null);
        String n2 = o2.get("number").asText();
        send(post("/api/orders/" + n2 + "/payments").header("X-Guest-Order-Token", o2.get("guestAccessToken").asText())
                .header("Idempotency-Key", "pay-key-0100"), null, Map.of("operator", "MTN_MOMO", "phone", "677123456"))
                .andExpect(status().isAccepted());
        webhook(json.writeValueAsString(Map.of("eventId", "evt-3", "reference", providerRef(n2), "status", "SUCCEEDED",
                "amount", 100, "currency", "XAF")), true).andExpect(status().isOk());
        send(get("/api/orders/" + n2), admin, null).andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.timeline[?(@.label=='Paiement à vérifier : montant ou devise incohérent')]").exists());
    }

    // ------------------------------------------------------------------ 14. échec, réessai, expiration, passage à la livraison
    @Test @Order(14)
    void failed_payment_retry_expiry_and_switch_to_cash_on_delivery() throws Exception {
        JsonNode o = placeOrder(customerB, List.of(item(p1, null, 1)), "MTN_MOMO", null);
        String n = o.get("number").asText();
        send(post("/api/orders/" + n + "/payments").header("Idempotency-Key", "retry-key-01"), customerB,
                Map.of("operator", "MTN_MOMO", "phone", "677123456")).andExpect(status().isAccepted());
        webhook(json.writeValueAsString(Map.of("eventId", "evt-10", "reference", providerRef(n), "status", "FAILED",
                "amount", 12_000, "currency", "XAF")), true).andExpect(status().isOk());
        send(get("/api/orders/" + n), customerB, null)
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        // Réessai autorisé, sans nouvelle commande
        JsonNode retry = body(send(post("/api/orders/" + n + "/payments").header("Idempotency-Key", "retry-key-02"), customerB,
                Map.of("operator", "ORANGE_MONEY", "phone", "699123456")).andExpect(status().isAccepted()));
        sql("update Payment p set p.expiresAt = :t where p.id = :id", "t", Instant.now().minusSeconds(5), "id", retry.get("paymentId").asText());
        paymentService.expirePayments();
        send(get("/api/orders/" + n), customerB, null).andExpect(jsonPath("$.paymentStatus").value("EXPIRED"));

        send(patch("/api/orders/" + n + "/payment-method"), customerB, Map.of("paymentMethod", "ORANGE_MONEY"))
                .andExpect(status().isBadRequest());
        send(patch("/api/orders/" + n + "/payment-method"), customerA, Map.of("paymentMethod", "CASH_ON_DELIVERY"))
                .andExpect(status().isForbidden());
        send(patch("/api/orders/" + n + "/payment-method"), customerB, Map.of("paymentMethod", "CASH_ON_DELIVERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"));
        send(post("/api/orders/" + n + "/payments").header("Idempotency-Key", "retry-key-03"), customerB,
                Map.of("operator", "ORANGE_MONEY", "phone", "699123456")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ 15. réservation expirée + paiement tardif
    @Test @Order(15)
    void reservation_expiry_restocks_and_late_payment_triggers_refund() throws Exception {
        assertThat(stockOf(p3Slug)).isEqualTo(7);
        JsonNode o = placeOrder(null, List.of(item(p3, "V-128", 1)), "ORANGE_MONEY", null);
        String n = o.get("number").asText();
        String token = o.get("guestAccessToken").asText();
        assertThat(stockOf(p3Slug)).isEqualTo(6);
        JsonNode p = body(send(post("/api/orders/" + n + "/payments").header("X-Guest-Order-Token", token)
                .header("Idempotency-Key", "late-key-01"), null, Map.of("operator", "ORANGE_MONEY", "phone", "699123456"))
                .andExpect(status().isAccepted()));
        String ref = providerRef(n);

        // Délai dépassé : paiement expiré puis réservation libérée (une seule restitution)
        sql("update Payment p set p.expiresAt = :t where p.id = :id", "t", Instant.now().minusSeconds(5), "id", p.get("paymentId").asText());
        sql("update CustomerOrder o set o.expiresAt = :t where o.number = :n", "t", Instant.now().minusSeconds(5), "n", n);
        paymentService.expirePayments();
        paymentService.releaseExpiredReservations();
        paymentService.releaseExpiredReservations();
        assertThat(stockOf(p3Slug)).isEqualTo(7);
        mvc.perform(get("/api/orders/" + n).header("X-Guest-Order-Token", token))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.reservationExpiresAt").doesNotExist());

        // Le stock est vendu à quelqu'un d'autre, puis le premier paiement aboutit quand même
        placeOrder(null, List.of(item(p3, "V-128", 5)), "CASH_ON_DELIVERY", null);
        webhook(json.writeValueAsString(Map.of("eventId", "evt-late", "reference", ref, "status", "SUCCEEDED",
                "amount", 102_000, "currency", "XAF")), true).andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + n).header("X-Guest-Order-Token", token))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));
        assertThat(body(mvc.perform(get("/api/products/" + p3Slug))).get("combinations").get(0).get("stock").asInt()).isZero();

        send(post("/api/admin/orders/" + n + "/mark-refunded"), admin, Map.of("reason", "Remboursé par Orange Money"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        send(post("/api/admin/orders/" + n + "/mark-refunded"), admin, null).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ 16. annulation client
    @Test @Order(16)
    void customer_cancellation_restocks_exactly_once() throws Exception {
        int before = stockOf(p1Slug);
        JsonNode o = placeOrder(null, List.of(item(p1, null, 2)), "CASH_ON_DELIVERY", null);
        String n = o.get("number").asText();
        String token = o.get("guestAccessToken").asText();
        assertThat(stockOf(p1Slug)).isEqualTo(before - 2);
        mvc.perform(post("/api/orders/" + n + "/cancel").header("X-Guest-Order-Token", "faux")).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + n + "/cancel").header("X-Guest-Order-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Erreur de commande\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/" + n + "/cancel").header("X-Guest-Order-Token", token)).andExpect(status().isOk());
        assertThat(stockOf(p1Slug)).isEqualTo(before);

        // Après préparation, le client ne peut plus annuler
        send(patch("/api/admin/orders/" + guestOrder + "/status"), admin, Map.of("status", "CONFIRMED")).andExpect(status().isOk());
        send(patch("/api/admin/orders/" + guestOrder + "/status"), admin, Map.of("status", "PREPARING")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + guestOrder + "/cancel").header("X-Guest-Order-Token", guestToken))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ 17. CORS et documentation
    @Test @Order(17)
    void cors_preflight_for_order_headers_and_openapi() throws Exception {
        mvc.perform(options("/api/orders")
                        .header("Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,idempotency-key,x-guest-order-token,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("idempotency-key")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-guest-order-token")));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/orders']").exists())
                .andExpect(jsonPath("$.paths['/api/checkout/quote']").exists());
        mvc.perform(get("/api/checkout/options")).andExpect(jsonPath("$.paymentMethods", not(hasItems("CASH"))));
    }
}
