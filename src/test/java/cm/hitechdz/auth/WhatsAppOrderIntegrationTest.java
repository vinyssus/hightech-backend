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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Commandes saisies par l'admin après WhatsApp + suivi par numéro et téléphone. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:whatsapporders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WhatsAppOrderIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static String admin, customer, productId, productSlug, guestOrder, guestKey;
    static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.9." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions adminOrder(String key, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/admin/orders");
        if (key != null) b.header("Idempotency-Key", key);
        return send(b, admin, body);
    }

    static Map<String, Object> order(int qty, String phone, String zone, String method) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("sku", null);
        item.put("qty", qty);
        item.put("price", 1);                       // ignoré : le serveur recalcule
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Awa N.");
        d.put("phone", phone);
        d.put("email", "");
        d.put("zoneId", zone);
        d.put("city", null);
        d.put("address", "Bonapriso, face pharmacie");
        d.put("notes", "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "WHATSAPP");
        m.put("items", List.of(item));
        m.put("delivery", d);
        m.put("paymentMethod", method);
        m.put("total", 10);                         // ignoré
        return m;
    }

    private int stock() throws Exception {
        return body(mvc.perform(get("/api/products/" + productSlug))).get("stock").asInt();
    }

    @Test @Order(1)
    void setup_and_security() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Marie", "lastName", "Ngo",
                "email", "marie@example.cm", "phone", "6 77 12 34 56", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Accessoires WA")).andExpect(status().isCreated());
        JsonNode p = body(send(post("/api/admin/products"), admin, Map.of("name", "Chargeur rapide 65W",
                "category", "accessoires-wa", "price", 25_000, "stock", 5, "status", "published")).andExpect(status().isCreated()));
        productId = p.get("id").asText();
        productSlug = p.get("slug").asText();
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", true)).andExpect(status().isOk());
        send(patch("/api/admin/shipping-zones/yaounde"), admin, Map.of("active", false)).andExpect(status().isOk());
        send(post("/api/admin/shipping-zones"), admin, Map.of("id", "bafoussam", "label", "Bafoussam", "fee", 4000,
                "delay", "48-72h", "codAllowed", false, "active", true)).andExpect(status().isCreated());

        send(post("/api/admin/orders").header("Idempotency-Key", UUID.randomUUID().toString()), null,
                order(1, "699000111", "douala", "CASH_ON_DELIVERY")).andExpect(status().isUnauthorized());
        send(post("/api/admin/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer,
                order(1, "699000111", "douala", "CASH_ON_DELIVERY")).andExpect(status().isForbidden());
        assertThat(stock()).isEqualTo(5);
    }

    @Test @Order(2)
    void validation_errors() throws Exception {
        adminOrder(null, order(1, "699000111", "douala", "CASH_ON_DELIVERY")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));
        Map<String, Object> bad = order(1, "12345", "douala", "BITCOIN");
        ((Map<String, Object>) bad.get("delivery")).put("address", "");
        ((Map<String, Object>) ((List<?>) bad.get("items")).get(0)).put("productId", "inconnu");
        bad.put("source", "WEB");
        adminOrder(UUID.randomUUID().toString(), bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['delivery.phone']").exists())
                .andExpect(jsonPath("$.fieldErrors['delivery.address']").exists())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").exists())
                .andExpect(jsonPath("$.fieldErrors.source").exists())
                .andExpect(jsonPath("$.fieldErrors['items[0]']").value("Produit introuvable ou non publié."));
        adminOrder(UUID.randomUUID().toString(), order(1, "699000111", "bafoussam", "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value("Le paiement à la livraison n'est pas disponible pour cette zone."));
        adminOrder(UUID.randomUUID().toString(), order(1, "699000111", "yaounde", "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['delivery.zoneId']").exists());
        adminOrder(UUID.randomUUID().toString(), order(0, "699000111", "douala", "CASH_ON_DELIVERY"))
                .andExpect(status().isBadRequest());
        Map<String, Object> empty = order(1, "699000111", "douala", "CASH_ON_DELIVERY");
        empty.put("items", List.of());
        adminOrder(UUID.randomUUID().toString(), empty).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.items").exists());
        assertThat(stock()).isEqualTo(5);
    }

    @Test @Order(3)
    void guest_order_confirmed_unpaid_with_server_prices_and_idempotent() throws Exception {
        guestKey = UUID.randomUUID().toString();
        JsonNode o = body(adminOrder(guestKey, order(2, "+237 6 99 00 01 11", "douala", "CASH_ON_DELIVERY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.source").value("WHATSAPP"))
                .andExpect(jsonPath("$.createdBy").isNotEmpty())
                .andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.userId").isEmpty())
                .andExpect(jsonPath("$.items[0].unitPrice").value(25_000))
                .andExpect(jsonPath("$.subtotal").value(50_000))
                .andExpect(jsonPath("$.shipping").value(1_000))
                .andExpect(jsonPath("$.total").value(51_000))
                .andExpect(jsonPath("$.delivery.delay").value("24h"))
                .andExpect(jsonPath("$.delivery.phone").value("+237699000111"))
                .andExpect(jsonPath("$.delivery.email").isEmpty())
                .andExpect(jsonPath("$.timeline[0].label").value("Commande enregistrée via WhatsApp"))
                .andExpect(jsonPath("$.timeline[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payments.length()").value(0))
                .andExpect(jsonPath("$.guestAccessToken").doesNotExist()));
        guestOrder = o.get("number").asText();
        assertThat(stock()).isEqualTo(3);

        // Même clé : même commande, aucun doublon, stock inchangé
        adminOrder(guestKey, order(2, "+237 6 99 00 01 11", "douala", "CASH_ON_DELIVERY"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(guestOrder));
        assertThat(stock()).isEqualTo(3);
        send(get("/api/admin/orders").param("q", guestOrder), admin, null).andExpect(jsonPath("$.totalElements").value(1));
        // Même clé, autre contenu : 409
        adminOrder(guestKey, order(1, "+237 6 99 00 01 11", "douala", "CASH_ON_DELIVERY"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("clé d'idempotence")));
        // Les clés admin ne se mélangent pas avec celles des clients : la commande n'est pas visible sans preuve
        mvc.perform(get("/api/orders/" + guestOrder)).andExpect(status().isUnauthorized());
    }

    @Test @Order(4)
    void insufficient_stock_is_409_and_nothing_reserved() throws Exception {
        adminOrder(UUID.randomUUID().toString(), order(4, "699000222", "douala", "CASH_ON_DELIVERY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Stock insuffisant pour certains articles."))
                .andExpect(jsonPath("$.fieldErrors['items[0]']").value("Plus que 3 en stock"));
        assertThat(stock()).isEqualTo(3);
    }

    @Test @Order(5)
    void phone_of_a_customer_account_attaches_the_order() throws Exception {
        JsonNode o = body(adminOrder(UUID.randomUUID().toString(), order(1, "677123456", "douala", "ORANGE_MONEY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guest").value(false))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentMethod").value("ORANGE_MONEY"))
                .andExpect(jsonPath("$.payments.length()").value(0)));
        String number = o.get("number").asText();
        send(get("/api/me/orders"), customer, null).andExpect(jsonPath("$.content[0].number").value(number));
        send(get("/api/orders/" + number), customer, null).andExpect(status().isOk());

        // Aucun paiement en ligne ne peut être lancé ; l'admin encaisse l'Orange Money reçu
        send(post("/api/orders/" + number + "/payments").header("Idempotency-Key", "wa-pay-0001"), customer,
                Map.of("operator", "ORANGE_MONEY", "phone", "699123456")).andExpect(status().isConflict());
        send(post("/api/admin/orders/" + number + "/collect-payment").header("Idempotency-Key", "wa-collect-01"), admin,
                Map.of("method", "ORANGE_MONEY", "reference", "OM 1234567")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.collection.amount").value(27_000));
        // Une commande du site payable à la livraison reste encaissable seulement une fois livrée
        send(post("/api/admin/orders/" + guestOrder + "/collect-payment").header("Idempotency-Key", "wa-collect-02"), admin,
                Map.of("method", "CASH")).andExpect(status().isConflict());
    }

    @Test @Order(6)
    void source_filter() throws Exception {
        send(get("/api/admin/orders").param("source", "WHATSAPP"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].source").value("WHATSAPP"));
        send(get("/api/admin/orders").param("source", "WEB"), admin, null).andExpect(jsonPath("$.totalElements").value(0));
        send(get("/api/admin/orders").param("source", "FAX"), admin, null).andExpect(status().isBadRequest());
        send(get("/api/admin/orders/" + guestOrder), admin, null).andExpect(jsonPath("$.source").value("WHATSAPP"));
    }

    @Test @Order(7)
    void lookup_by_number_and_phone() throws Exception {
        send(post("/api/orders/lookup"), null, Map.of("number", guestOrder, "phone", "699999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Commande introuvable. Vérifiez le numéro et le téléphone."));
        send(post("/api/orders/lookup"), null, Map.of("number", "HD-ZZZZZZZZ", "phone", "699000111"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Commande introuvable. Vérifiez le numéro et le téléphone."));
        send(post("/api/orders/lookup"), null, Map.of("number", guestOrder, "phone", "pas un numéro"))
                .andExpect(status().isNotFound());
        send(post("/api/orders/lookup"), null, Map.of("number", "", "phone", "")).andExpect(status().isBadRequest());

        String t1 = body(send(post("/api/orders/lookup"), null, Map.of("number", guestOrder.toLowerCase(), "phone", "6 99 00 01 11"))
                .andExpect(status().isOk())).get("guestAccessToken").asText();
        String t2 = body(send(post("/api/orders/lookup"), null, Map.of("number", guestOrder, "phone", "+237699000111"))
                .andExpect(status().isOk())).get("guestAccessToken").asText();
        assertThat(t1).isNotEqualTo(t2).hasSizeGreaterThan(30);
        for (String t : List.of(t1, t2)) {    // le nouveau jeton n'invalide pas le précédent
            mvc.perform(get("/api/orders/" + guestOrder).header("X-Guest-Order-Token", t))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.number").value(guestOrder))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        mvc.perform(get("/api/orders/" + guestOrder).header("X-Guest-Order-Token", "faux-jeton")).andExpect(status().isForbidden());
    }

    @Test @Order(8)
    void lookup_rate_limited_per_ip() throws Exception {
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = mvc.perform(post("/api/orders/lookup").with(r -> { r.setRemoteAddr("10.99.0.1"); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("number", guestOrder, "phone", "699999999"))))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        mvc.perform(post("/api/orders/lookup").with(r -> { r.setRemoteAddr("10.99.0.1"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("number", guestOrder, "phone", "699000111"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
