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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrat des avis clients attendu par le frontend. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reviewcontract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewContractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static String admin, viny, alice, productId, slug;
    static long vinyReview, aliceReview;
    static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.8." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String register(String first, String last, String email) throws Exception {
        return body(send(post("/api/auth/register"), null, Map.of("firstName", first, "lastName", last,
                "email", email, "phone", "6 70 00 01 " + (10 + IP.get() % 80), "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
    }

    private static Map<String, Object> review(Object rating, String title, String body, String city) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rating", rating);
        m.put("title", title);
        m.put("body", body);
        m.put("city", city);
        return m;
    }

    @Test @Order(1)
    void setup_with_a_delivered_order_for_viny() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        viny = register("Viny", "rodolphe", "viny@example.cm");
        alice = register("Alice", "Mbarga", "alice@example.cm");
        send(post("/api/admin/categories"), admin, Map.of("name", "Téléphones")).andExpect(status().isCreated());
        JsonNode p = body(send(post("/api/admin/products"), admin, Map.of("name", "iPhone 15 Pro Max",
                "category", "telephones", "price", 900_000, "stock", 5, "status", "published")).andExpect(status().isCreated()));
        productId = p.get("id").asText();
        slug = p.get("slug").asText();

        // Commande livrée pour Viny → avis « achat vérifié »
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", true)).andExpect(status().isOk());
        List<Map<String, Object>> items = List.of(Map.of("productId", productId, "qty", 1));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("items", items);
        q.put("zoneId", "douala");
        String quoteId = body(send(post("/api/checkout/quote"), viny, q).andExpect(status().isOk())).get("quoteId").asText();
        String number = body(send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), viny,
                Map.of("quoteId", quoteId, "items", items, "paymentMethod", "CASH_ON_DELIVERY",
                        "delivery", Map.of("name", "Viny R", "phone", "699123456", "zoneId", "douala", "address", "Akwa"))))
                .get("number").asText();
        for (String s : List.of("CONFIRMED", "PREPARING", "SHIPPED", "DELIVERED")) {
            send(patch("/api/admin/orders/" + number + "/status"), admin, Map.of("status", s)).andExpect(status().isOk());
        }
    }

    @Test @Order(2)
    void public_list_shape_and_404() throws Exception {
        mvc.perform(get("/api/products/" + slug + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.summary.average").value(0.0))
                .andExpect(jsonPath("$.summary.count").value(0))
                .andExpect(jsonPath("$.summary.distribution.1").value(0))
                .andExpect(jsonPath("$.summary.distribution.5").value(0));
        mvc.perform(get("/api/products/produit-inconnu/reviews")).andExpect(status().isNotFound());
    }

    @Test @Order(3)
    void validation_and_authentication() throws Exception {
        send(post("/api/products/" + slug + "/reviews"), null, review(5, "Excellent", "Très bon produit vraiment.", null))
                .andExpect(status().isUnauthorized());
        send(post("/api/products/" + slug + "/reviews"), viny, review(null, "Ok", "court", "x".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rating").exists())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.body").exists())
                .andExpect(jsonPath("$.fieldErrors.city").exists());
        send(post("/api/products/" + slug + "/reviews"), viny, review(6, "Excellent", "Très bon produit vraiment.", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.rating").exists());
        send(post("/api/products/" + slug + "/reviews"), viny, review(5, "Excellent", "x".repeat(2001), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").value("L'avis doit contenir entre 10 et 2000 caractères."));
        // Que du HTML : vide une fois nettoyé → refusé
        send(post("/api/products/" + slug + "/reviews"), viny,
                review(5, "<b></b><i></i>", "<script>alert('xss')</script><img src=x onerror=alert(1)>", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.body").exists());
        send(post("/api/products/produit-inconnu/reviews"), viny, review(5, "Excellent", "Très bon produit vraiment.", null))
                .andExpect(status().isNotFound());
    }

    @Test @Order(4)
    void create_verified_sanitized_and_duplicate_409() throws Exception {
        JsonNode r = body(send(post("/api/products/" + slug + "/reviews"), viny, review(5, "Excellent <b>produit</b>",
                "Livré rapidement à Douala, <script>alert('x')</script>conforme à la description.", " Douala "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.title").value("Excellent produit"))
                .andExpect(jsonPath("$.body").value("Livré rapidement à Douala, conforme à la description."))
                .andExpect(jsonPath("$.city").value("Douala"))
                .andExpect(jsonPath("$.author").value("Viny R."))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()));
        vinyReview = r.get("id").asLong();
        String raw = r.toString();
        assertThat(raw).doesNotContain("viny@example.cm", "699123456", "userId");

        send(post("/api/products/" + slug + "/reviews"), viny, review(4, "Deuxième avis", "Je change d'avis sur ce produit.", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vous avez déjà donné votre avis sur ce produit."));

        JsonNode a = body(send(post("/api/products/" + slug + "/reviews"), alice,
                review(2, "Déçue", "La batterie ne tient pas la journée.", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.author").value("Alice M."))
                .andExpect(jsonPath("$.city").isEmpty()));
        aliceReview = a.get("id").asLong();

        // Note du catalogue cohérente avec les avis
        mvc.perform(get("/api/products/" + slug)).andExpect(jsonPath("$.rating").value(3.5)).andExpect(jsonPath("$.reviews").value(2));
        mvc.perform(get("/api/products").param("q", "iphone")).andExpect(jsonPath("$.content[0].rating").value(3.5));
    }

    @Test @Order(5)
    void helpful_once_per_user() throws Exception {
        send(post("/api/reviews/" + vinyReview + "/helpful"), null, null).andExpect(status().isUnauthorized());
        send(post("/api/reviews/" + vinyReview + "/helpful"), alice, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.helpfulCount").value(1));
        send(post("/api/reviews/" + vinyReview + "/helpful"), alice, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vous avez déjà trouvé cet avis utile."));
        send(post("/api/reviews/" + vinyReview + "/helpful"), admin, null).andExpect(jsonPath("$.helpfulCount").value(2));
        send(post("/api/reviews/999999/helpful"), alice, null).andExpect(status().isNotFound());
    }

    @Test @Order(6)
    void sorting_and_summary() throws Exception {
        mvc.perform(get("/api/products/" + slug + "/reviews"))
                .andExpect(jsonPath("$.content[0].id").value(aliceReview))           // recent par défaut
                .andExpect(jsonPath("$.summary.average").value(3.5))
                .andExpect(jsonPath("$.summary.count").value(2))
                .andExpect(jsonPath("$.summary.distribution.5").value(1))
                .andExpect(jsonPath("$.summary.distribution.2").value(1))
                .andExpect(jsonPath("$.summary.distribution.3").value(0));
        mvc.perform(get("/api/products/" + slug + "/reviews").param("sort", "rating"))
                .andExpect(jsonPath("$.content[0].id").value(vinyReview));
        mvc.perform(get("/api/products/" + slug + "/reviews").param("sort", "helpful"))
                .andExpect(jsonPath("$.content[0].helpfulCount").value(2));
        mvc.perform(get("/api/products/" + slug + "/reviews").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test @Order(7)
    void admin_list_and_delete_recomputes_rating() throws Exception {
        send(get("/api/admin/reviews"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/reviews"), alice, null).andExpect(status().isForbidden());
        send(delete("/api/admin/reviews/" + aliceReview), alice, null).andExpect(status().isForbidden());

        send(get("/api/admin/reviews").param("page", "0").param("size", "20"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(String.valueOf(aliceReview)))
                .andExpect(jsonPath("$.content[0].productSlug").value(slug))
                .andExpect(jsonPath("$.content[0].productName").value("iPhone 15 Pro Max"))
                .andExpect(jsonPath("$.content[0].author").value("Alice M."))
                .andExpect(jsonPath("$.content[0].rating").value(2))
                .andExpect(jsonPath("$.content[0].verified").value(false))
                .andExpect(jsonPath("$.content[1].city").value("Douala"));

        send(delete("/api/admin/reviews/" + aliceReview), admin, null).andExpect(status().isNoContent());
        send(delete("/api/admin/reviews/" + aliceReview), admin, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/" + slug)).andExpect(jsonPath("$.rating").value(5.0)).andExpect(jsonPath("$.reviews").value(1));
        mvc.perform(get("/api/products/" + slug + "/reviews")).andExpect(jsonPath("$.summary.count").value(1));

        send(delete("/api/admin/reviews/" + vinyReview), admin, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/products/" + slug)).andExpect(jsonPath("$.rating").value(0.0)).andExpect(jsonPath("$.reviews").value(0));
    }

    @Test @Order(8)
    void cors_preflight() throws Exception {
        mvc.perform(options("/api/products/" + slug + "/reviews")
                        .header("Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app"));
    }
}
