package cm.hitechdz.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrat des zones de livraison : admin, données initiales, options publiques, devis et commandes. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shippingzones;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShippingZoneIntegrationTest {

    private static final String ZONES = "/api/admin/shipping-zones";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    static String admin, customer, productId;
    private static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.7." + (n / 250) + "." + (n % 250)); return r; });
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

    private static Map<String, Object> zone(String label, long fee, String delay) {
        Map<String, Object> z = new HashMap<>();
        z.put("label", label);
        z.put("fee", fee);
        z.put("delay", delay);
        return z;
    }

    private ResultActions quote(String zoneId, String city) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("items", List.of(Map.of("productId", productId, "qty", 1)));
        b.put("zoneId", zoneId);
        b.put("city", city);
        return send(post("/api/checkout/quote"), null, b);
    }

    @Test @Order(1)
    void seed_is_active_sorted_and_never_duplicated_on_restart() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Zoe", "lastName", "Zone",
                "email", "zoe.zone@example.cm", "phone", "6 70 00 00 09", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Zones")).andExpect(status().isCreated());
        productId = body(send(post("/api/admin/products"), admin, Map.of("name", "Clavier", "category", "zones",
                "price", 10_000, "stock", 50, "status", "published")).andExpect(status().isCreated())).get("id").asText();

        JsonNode o = options();
        assertThat(o.get("shippingZones").findValuesAsText("label")).containsExactly(
                "Douala", "Yaoundé", "Retrait en boutique (Douala)", "Autres villes du Cameroun");
        assertThat(o.get("shippingZones").findValuesAsText("delay")).containsExactly("24h", "24-48h", "Même jour", "2-5 jours");
        assertThat(o.get("paymentMethods").toString()).contains("CASH_ON_DELIVERY");

        // Redémarrage : Flyway n'a plus rien à appliquer, les zones ne sont ni recréées ni écrasées
        send(put(ZONES + "/douala"), admin, Map.of("fee", 1200)).andExpect(status().isOk());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from shipping_zones", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select fee from shipping_zones where id = 'douala'", Long.class)).isEqualTo(1200);
        send(put(ZONES + "/douala"), admin, Map.of("fee", 1000)).andExpect(status().isOk());
        // Le schéma n'est jamais recréé par Hibernate
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test @Order(2)
    void admin_routes_require_admin_role() throws Exception {
        send(get(ZONES), null, null).andExpect(status().isUnauthorized());
        send(get(ZONES), customer, null).andExpect(status().isForbidden());
        send(post(ZONES), customer, zone("Kribi", 2500, "48h")).andExpect(status().isForbidden());
        send(put(ZONES + "/douala"), customer, zone("Douala", 0, "24h")).andExpect(status().isForbidden());
        send(delete(ZONES + "/douala"), customer, null).andExpect(status().isForbidden());
        send(patch(ZONES + "/reorder"), customer, Map.of("ids", List.of("autre"))).andExpect(status().isForbidden());
    }

    @Test @Order(3)
    void create_update_with_defaults_and_validations() throws Exception {
        JsonNode created = body(send(post(ZONES), admin, zone("Kribi", 2500, "48-72h")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("kribi"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.codAllowed").value(false))
                .andExpect(jsonPath("$.requiresCity").value(false))
                .andExpect(jsonPath("$.pickup").value(false))
                .andExpect(jsonPath("$.position").value(5))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists()));
        assertThat(created.get("fee").asLong()).isEqualTo(2500);

        Map<String, Object> full = zone("Kribi plage", 2800, "48h");
        full.put("codAllowed", true);
        full.put("active", true);
        full.put("requiresCity", false);
        full.put("pickup", false);
        full.put("position", 5);
        send(put(ZONES + "/kribi"), admin, full).andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Kribi plage"))
                .andExpect(jsonPath("$.fee").value(2800))
                .andExpect(jsonPath("$.codAllowed").value(true))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()));

        // Libellé en double, insensible à la casse : création comme modification
        send(post(ZONES), admin, zone("  douala ", 900, "24h")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors.label").exists());
        send(post(ZONES), admin, zone("YAOUNDÉ", 900, "24h")).andExpect(status().isConflict());
        send(put(ZONES + "/kribi"), admin, Map.of("label", "DOUALA")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        // Validations
        send(post(ZONES), admin, zone("Limbé", -1, "48h")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fee").exists());
        send(post(ZONES), admin, zone("Limbé", 100_001, "48h")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fee").value("Les frais ne peuvent pas dépasser 100 000 FCFA."));
        send(post(ZONES), admin, zone("L", 1000, "48h")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.label").exists());
        send(post(ZONES), admin, zone("Limbé", 1000, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.delay").exists());
        send(post(ZONES), admin, zone("Limbé", 1000, "x".repeat(41))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.delay").exists());
        Map<String, Object> both = zone("Limbé", 1000, "48h");
        both.put("pickup", true);
        both.put("requiresCity", true);
        send(post(ZONES), admin, both).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.pickup").exists())
                .andExpect(jsonPath("$.fieldErrors.requiresCity").exists());
        send(put(ZONES + "/autre"), admin, Map.of("pickup", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.pickup").exists());
        send(put(ZONES + "/inconnue"), admin, zone("Inconnue", 1, "24h")).andExpect(status().isNotFound());
        send(delete(ZONES + "/inconnue"), admin, null).andExpect(status().isNotFound());

        // Rien n'a été modifié par les requêtes refusées
        send(get(ZONES), admin, null).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.id=='autre')].pickup").value(false));
    }

    @Test @Order(4)
    void unused_zone_is_deleted_and_reorder_changes_positions() throws Exception {
        send(delete(ZONES + "/kribi"), admin, null).andExpect(status().isNoContent());
        send(get(ZONES), admin, null).andExpect(jsonPath("$.length()").value(4));

        send(patch(ZONES + "/reorder"), admin, Map.of("ids", List.of("autre", "douala"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("autre"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].id").value("douala"))
                .andExpect(jsonPath("$[2].id").value("yaounde"))
                .andExpect(jsonPath("$[3].id").value("retrait"))
                .andExpect(jsonPath("$[3].position").value(4));
        assertThat(options().get("shippingZones").findValuesAsText("id")).containsExactly("autre", "douala", "yaounde", "retrait");
        send(patch(ZONES + "/reorder"), admin, Map.of("ids", List.of("douala", "fantome"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ids").exists());
        send(patch(ZONES + "/reorder"), admin, Map.of("ids", List.of("douala", "yaounde", "retrait", "autre")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[3].id").value("autre"));
    }

    @Test @Order(5)
    void options_list_active_zones_and_cash_on_delivery_follows_cod_allowed() throws Exception {
        send(put(ZONES + "/yaounde"), admin, Map.of("active", false)).andExpect(status().isOk());
        assertThat(options().get("shippingZones").findValuesAsText("id")).containsExactly("douala", "retrait", "autre");

        // Plus aucune zone active avec paiement à la livraison → CASH_ON_DELIVERY disparaît
        send(put(ZONES + "/douala"), admin, Map.of("codAllowed", false)).andExpect(status().isOk());
        send(put(ZONES + "/retrait"), admin, Map.of("codAllowed", false)).andExpect(status().isOk());
        assertThat(options().get("paymentMethods").toString()).doesNotContain("CASH_ON_DELIVERY");
        send(put(ZONES + "/retrait"), admin, Map.of("codAllowed", true)).andExpect(status().isOk());
        assertThat(options().get("paymentMethods").toString()).contains("CASH_ON_DELIVERY");
        send(put(ZONES + "/douala"), admin, Map.of("codAllowed", true)).andExpect(status().isOk());
    }

    @Test @Order(6)
    void quote_uses_server_fees_and_zone_rules() throws Exception {
        quote("yaounde", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.zoneId").value("Zone de livraison indisponible"));
        quote("nulle-part", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.zoneId").value("Zone de livraison indisponible"));
        quote("autre", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.city").value("Indiquez votre ville"));
        quote("autre", "Bafoussam").andExpect(status().isOk())
                .andExpect(jsonPath("$.shipping").value(3000)).andExpect(jsonPath("$.total").value(13_000));
        quote("douala", null).andExpect(status().isOk()).andExpect(jsonPath("$.shipping").value(1000));
        quote("retrait", null).andExpect(status().isOk()).andExpect(jsonPath("$.shipping").value(0));
        send(put(ZONES + "/yaounde"), admin, Map.of("active", true)).andExpect(status().isOk());
        quote("yaounde", null).andExpect(status().isOk()).andExpect(jsonPath("$.shipping").value(1500));
    }

    @Test @Order(7)
    void orders_snapshot_zone_and_used_zone_is_only_deactivated() throws Exception {
        String quoteId = body(quote("retrait", null).andExpect(status().isOk())).get("quoteId").asText();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Zoe Zone");
        d.put("phone", "699123499");
        d.put("zoneId", "retrait");           // retrait : adresse facultative
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quoteId", quoteId);
        b.put("items", List.of(Map.of("productId", productId, "qty", 1)));
        b.put("delivery", d);
        b.put("paymentMethod", "CASH_ON_DELIVERY");
        b.put("shipping", 99_999);           // ignoré : frais calculés par le serveur
        String number = body(send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer, b)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipping").value(0))
                .andExpect(jsonPath("$.delivery.zoneId").value("retrait"))
                .andExpect(jsonPath("$.delivery.zoneLabel").value("Retrait en boutique (Douala)"))
                .andExpect(jsonPath("$.delivery.delay").value("Même jour"))).get("number").asText();

        // Adresse obligatoire hors retrait ; paiement à la livraison refusé sur « autres villes »
        d.put("zoneId", "douala");
        b.put("quoteId", body(quote("douala", null)).get("quoteId").asText());
        send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer, b)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['delivery.address']").exists());
        d.put("zoneId", "autre");
        d.put("city", "Bafoussam");
        d.put("address", "Marché A");
        b.put("quoteId", body(quote("autre", "Bafoussam")).get("quoteId").asText());
        send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer, b)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.paymentMethod").exists());

        // Zone utilisée : désactivée (200), pas supprimée ; la commande garde sa copie
        send(delete(ZONES + "/retrait"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("retrait"))
                .andExpect(jsonPath("$.active").value(false));
        send(get(ZONES), admin, null).andExpect(jsonPath("$[?(@.id=='retrait')].active").value(false));
        assertThat(options().get("shippingZones").findValuesAsText("id")).doesNotContain("retrait");
        send(get("/api/admin/orders/" + number), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.zoneLabel").value("Retrait en boutique (Douala)"));
        send(put(ZONES + "/retrait"), admin, Map.of("active", true)).andExpect(status().isOk());
    }
}
