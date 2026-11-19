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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tableau de bord admin calculé à partir de vraies commandes et visites (aucune donnée inventée). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static String admin, customer, phone, accessory, cable;
    private static final AtomicInteger IP = new AtomicInteger(1);

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

    private JsonNode dashboard(int days) throws Exception {
        return body(send(get("/api/admin/dashboard").param("days", String.valueOf(days)), admin, null).andExpect(status().isOk()));
    }

    private String product(String name, String category, long price, int stock) throws Exception {
        return body(send(post("/api/admin/products"), admin, Map.of("name", name, "category", category,
                "price", price, "stock", stock, "status", "published")).andExpect(status().isCreated())).get("id").asText();
    }

    private String order(Map<String, Integer> lines) throws Exception {
        List<Map<String, Object>> items = lines.entrySet().stream()
                .map(e -> Map.<String, Object>of("productId", e.getKey(), "qty", e.getValue())).toList();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("items", items);
        q.put("zoneId", "douala");
        String quoteId = body(send(post("/api/checkout/quote"), customer, q).andExpect(status().isOk())).get("quoteId").asText();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quoteId", quoteId);
        b.put("items", items);
        b.put("delivery", Map.of("name", "Awa Ngono", "phone", "699112233", "zoneId", "douala", "address", "Bonapriso"));
        b.put("paymentMethod", "CASH_ON_DELIVERY");
        return body(send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer, b)
                .andExpect(status().isCreated())).get("number").asText();
    }

    @Test @Order(1)
    void empty_shop_has_zeros_and_no_invented_rate() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Awa", "lastName", "Ngono",
                "email", "awa.dash@example.cm", "phone", "6 99 11 22 33", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();

        send(get("/api/admin/dashboard"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/dashboard"), customer, null).andExpect(status().isForbidden());
        send(get("/api/admin/dashboard").param("days", "15"), admin, null).andExpect(status().isBadRequest());

        JsonNode d = dashboard(30);
        assertThat(d.get("days").asInt()).isEqualTo(30);
        assertThat(d.get("series").size()).isEqualTo(30);
        assertThat(d.at("/kpis/revenue/value").asDouble()).isZero();
        assertThat(d.at("/kpis/revenue/delta").isNull()).isTrue();      // pas de période précédente
        assertThat(d.at("/kpis/basket/value").isNull()).isTrue();
        assertThat(d.at("/kpis/conversion/value").isNull()).isTrue();   // aucune visite : pas de taux inventé
        assertThat(d.at("/summary/orders").asLong()).isZero();
        assertThat(d.get("recent").size()).isZero();
        assertThat(d.get("top").size()).isZero();
    }

    @Test @Order(2)
    void visits_are_anonymous_and_counted_once_per_day() throws Exception {
        String a = UUID.randomUUID().toString();
        send(post("/api/analytics/visit"), null, Map.of("visitorId", a)).andExpect(status().isNoContent());
        send(post("/api/analytics/visit"), null, Map.of("visitorId", a)).andExpect(status().isNoContent());
        send(post("/api/analytics/visit"), null, Map.of("visitorId", UUID.randomUUID().toString())).andExpect(status().isNoContent());
        send(post("/api/analytics/visit"), null, Map.of("visitorId", "x")).andExpect(status().isBadRequest());
        send(post("/api/analytics/visit"), null, Map.of("visitorId", "<script>alert(1)</script>")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from site_visits", Integer.class)).isEqualTo(2);
        // Seule l'empreinte est conservée, jamais l'identifiant envoyé
        assertThat(jdbc.queryForList("select visitor_hash from site_visits", String.class)).doesNotContain(a).allMatch(h -> h.length() == 64);
        assertThat(dashboard(30).at("/kpis/visitors/value").asLong()).isEqualTo(2);
    }

    @Test @Order(3)
    void figures_come_from_real_orders() throws Exception {
        send(post("/api/admin/categories"), admin, Map.of("name", "Téléphones")).andExpect(status().isCreated());
        send(post("/api/admin/categories"), admin, Map.of("name", "Accessoires")).andExpect(status().isCreated());
        phone = product("Tecno Camon 30", "telephones", 100_000, 10);
        accessory = product("Écouteurs Oraimo", "accessoires", 5_000, 3);
        cable = product("Câble USB-C", "accessoires", 2_000, 0);

        // o1 : passée il y a 40 jours (période précédente pour 30 j)
        String o1 = order(Map.of(phone, 1));
        jdbc.update("update orders set created_at = ? where number = ?",
                Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)), o1);
        // o2 : annulée, exclue du chiffre d'affaires
        String o2 = order(Map.of(accessory, 2));
        send(post("/api/orders/" + o2 + "/cancel"), customer, Map.of("reason", "test")).andExpect(status().isOk());
        // o3 : livrée et encaissée
        String o3 = order(Map.of(phone, 1, accessory, 1));
        for (String s : List.of("CONFIRMED", "PREPARING", "SHIPPED", "DELIVERED")) {
            send(patch("/api/admin/orders/" + o3 + "/status"), admin, Map.of("status", s)).andExpect(status().isOk());
        }
        send(post("/api/admin/orders/" + o3 + "/collect-payment").header("Idempotency-Key", "dash-collect-1"), admin,
                Map.of("method", "CASH")).andExpect(status().isOk());
        // o4 : en attente de confirmation
        String o4 = order(Map.of(accessory, 1));

        JsonNode d = dashboard(30);
        // CA = o3 (100 000 + 5 000 + 1 000 livraison) + o4 (5 000 + 1 000)
        assertThat(d.at("/kpis/revenue/value").asLong()).isEqualTo(112_000);
        assertThat(d.at("/kpis/revenue/previous").asLong()).isEqualTo(101_000);
        assertThat(d.at("/kpis/revenue/delta").asDouble()).isEqualTo(10.89);
        assertThat(d.at("/kpis/orders/value").asLong()).isEqualTo(2);
        assertThat(d.at("/kpis/orders/delta").asDouble()).isEqualTo(100.0);
        assertThat(d.at("/kpis/basket/value").asLong()).isEqualTo(56_000);
        assertThat(d.at("/kpis/conversion/value").asDouble()).isEqualTo(100.0);   // 2 commandes / 2 visites
        assertThat(d.at("/kpis/conversion/delta").isNull()).isTrue();

        assertThat(d.at("/summary/orders").asLong()).isEqualTo(2);
        assertThat(d.at("/summary/collected").asLong()).isEqualTo(106_000);
        assertThat(d.at("/summary/pending").asLong()).isEqualTo(1);
        assertThat(d.at("/summary/delivered").asLong()).isEqualTo(1);

        long seriesTotal = 0, previousTotal = 0;
        for (JsonNode p : d.get("series")) { seriesTotal += p.get("revenue").asLong(); previousTotal += p.get("previous").asLong(); }
        assertThat(seriesTotal).isEqualTo(112_000);
        assertThat(previousTotal).isEqualTo(101_000);
        assertThat(d.get("series").get(29).get("orders").asLong()).isEqualTo(2);   // aujourd'hui

        JsonNode cash = d.get("payments").get(2);
        assertThat(cash.get("key").asText()).isEqualTo("cash");
        assertThat(cash.get("amount").asLong()).isEqualTo(112_000);
        assertThat(cash.get("share").asDouble()).isEqualTo(1.0);
        assertThat(d.get("payments").get(0).get("amount").asLong()).isZero();

        assertThat(d.get("categories").findValuesAsText("name")).containsExactly("Téléphones", "Accessoires");
        assertThat(d.get("categories").get(1).get("amount").asLong()).isEqualTo(10_000);

        JsonNode st = d.get("status");
        assertThat(st.findValuesAsText("key")).containsExactly("pending", "preparing", "shipping", "delivered", "cancelled");
        assertThat(st.get(0).get("count").asLong()).isEqualTo(1);
        assertThat(st.get(3).get("count").asLong()).isEqualTo(1);
        assertThat(st.get(4).get("count").asLong()).isEqualTo(1);

        // Écouteurs : 3 - 2 (o2) + 2 (annulation) - 1 (o3) - 1 (o4) = 1 ; câble en rupture
        assertThat(d.at("/lowStock/threshold").asInt()).isEqualTo(5);
        assertThat(d.at("/lowStock/items").findValuesAsText("name")).containsExactly("Câble USB-C", "Écouteurs Oraimo");
        assertThat(d.at("/lowStock/items/0/stock").asInt()).isZero();
        assertThat(d.at("/lowStock/items/1/stock").asInt()).isEqualTo(1);

        assertThat(d.get("top").get(0).get("id").asText()).isEqualTo(accessory);
        assertThat(d.get("top").get(0).get("sold").asLong()).isEqualTo(2);
        assertThat(d.get("top").get(1).get("revenue").asLong()).isEqualTo(100_000);

        JsonNode recent = d.get("recent");
        assertThat(recent.size()).isEqualTo(4);
        assertThat(recent.get(0).get("number").asText()).isEqualTo(o4);
        assertThat(recent.get(0).get("statusKey").asText()).isEqualTo("pending");
        assertThat(recent.get(0).get("customer").asText()).isEqualTo("Awa Ngono");
        assertThat(recent.get(0).get("city").asText()).isEqualTo("Douala");
        assertThat(recent.get(0).get("payment").asText()).isEqualTo("À la livraison");
        assertThat(recent.get(1).get("statusLabel").asText()).isEqualTo("Livrée");

        // 7 jours : o1 hors des deux périodes
        JsonNode w = dashboard(7);
        assertThat(w.get("series").size()).isEqualTo(7);
        assertThat(w.at("/kpis/revenue/value").asLong()).isEqualTo(112_000);
        assertThat(w.at("/kpis/revenue/delta").isNull()).isTrue();
    }
}
