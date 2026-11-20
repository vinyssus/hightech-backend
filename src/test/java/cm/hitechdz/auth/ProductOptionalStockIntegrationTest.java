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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stock facultatif : produit sans stock = non suivi, vendable, jamais affiché ni en alerte. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:optionalstock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductOptionalStockIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static String admin, customer, id, slug;
    private static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.11." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static boolean noStock(JsonNode n) {
        return n.get("stock") == null || n.get("stock").isNull();
    }

    private Map<String, Object> productBody(Integer stock) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Routeur Wi-Fi 6");
        b.put("category", "reseau");
        b.put("price", 25_000);
        b.put("status", "published");
        if (stock != null) b.put("stock", stock);
        return b;
    }

    private String order(int qty) throws Exception {
        List<Map<String, Object>> items = List.of(Map.of("productId", id, "qty", qty));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("items", items);
        q.put("zoneId", "douala");
        String quoteId = body(send(post("/api/checkout/quote"), customer, q).andExpect(status().isOk())).get("quoteId").asText();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quoteId", quoteId);
        b.put("items", items);
        b.put("delivery", Map.of("name", "Paul Eto", "phone", "699445566", "zoneId", "douala", "address", "Akwa"));
        b.put("paymentMethod", "CASH_ON_DELIVERY");
        return body(send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), customer, b)
                .andExpect(status().isCreated())).get("number").asText();
    }

    private int dbStock() {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, id);
    }

    private boolean dbTracked() {
        return jdbc.queryForObject("select stock_tracked from products where id = ?", Boolean.class, id);
    }

    @Test @Order(1)
    void product_without_stock_is_created_and_not_shown() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Paul", "lastName", "Eto",
                "email", "paul.stock@example.cm", "phone", "6 99 44 55 66", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Réseau")).andExpect(status().isCreated());

        JsonNode created = body(send(post("/api/admin/products"), admin, productBody(null)).andExpect(status().isCreated()));
        id = created.get("id").asText();
        slug = created.get("slug").asText();
        assertThat(noStock(created)).isTrue();
        assertThat(dbTracked()).isFalse();

        assertThat(noStock(body(send(get("/api/products/" + slug), null, null).andExpect(status().isOk())))).isTrue();
        JsonNode list = body(send(get("/api/products").param("inStock", "true").param("cat", "reseau"), null, null)
                .andExpect(status().isOk()));
        JsonNode first = list.has("content") ? list.get("content").get(0) : list.get(0);
        assertThat(first.get("id").asText()).isEqualTo(id);          // jamais « en rupture »
        assertThat(noStock(first)).isTrue();

        // Panier : disponible, quantité non limitée par un stock, aucun message de stock
        JsonNode cart = body(send(post("/api/cart/validate"), null, Map.of("items", List.of(Map.of("productId", id, "qty", 8))))
                .andExpect(status().isOk()));
        JsonNode line = cart.get("items").get(0);
        assertThat(line.get("available").asBoolean()).isTrue();
        assertThat(line.get("qty").asInt()).isEqualTo(8);
        assertThat(noStock(line)).isTrue();
        assertThat(line.get("message") == null || line.get("message").isNull()).isTrue();
    }

    @Test @Order(2)
    void untracked_product_sells_without_limit_and_is_never_low_stock() throws Exception {
        String o1 = order(10);
        String o2 = order(10);
        assertThat(dbStock()).isZero();
        assertThat(dbTracked()).isFalse();
        send(post("/api/orders/" + o1 + "/cancel"), customer, Map.of("reason", "test")).andExpect(status().isOk());
        assertThat(dbStock()).isZero();                                   // rien remis en stock

        JsonNode low = body(send(get("/api/admin/products/low-stock"), admin, null).andExpect(status().isOk()));
        assertThat(low.findValuesAsText("id")).doesNotContain(id);
        JsonNode dash = body(send(get("/api/admin/dashboard"), admin, null).andExpect(status().isOk()));
        assertThat(dash.at("/lowStock/items").findValuesAsText("id")).doesNotContain(id);

        // L'admin renseigne ensuite un stock : produit suivi ; o2 (passée sans suivi) ne le gonfle pas à l'annulation
        Map<String, Object> withStock = productBody(4);
        assertThat(body(send(put("/api/admin/products/" + id), admin, withStock).andExpect(status().isOk())).get("stock").asInt())
                .isEqualTo(4);
        send(post("/api/orders/" + o2 + "/cancel"), customer, Map.of("reason", "test")).andExpect(status().isOk());
        assertThat(dbStock()).isEqualTo(4);
        assertThat(body(send(get("/api/products/" + slug), null, null)).get("stock").asInt()).isEqualTo(4);
        assertThat(body(send(get("/api/admin/products/low-stock"), admin, null)).findValuesAsText("id")).contains(id);

        // Suivi : la limite s'applique de nouveau
        JsonNode cart = body(send(post("/api/cart/validate"), null, Map.of("items", List.of(Map.of("productId", id, "qty", 6)))));
        JsonNode line = cart.get("items").get(0);
        assertThat(line.get("qty").asInt()).isEqualTo(4);

        // Champ vidé : de nouveau non suivi
        assertThat(noStock(body(send(put("/api/admin/products/" + id), admin, productBody(null)).andExpect(status().isOk())))).isTrue();
        assertThat(dbTracked()).isFalse();
    }
}
