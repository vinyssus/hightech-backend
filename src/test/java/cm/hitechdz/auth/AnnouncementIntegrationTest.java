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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bandeau d'annonces : liste publique filtrée, administration, validation. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:announcements;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnnouncementIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static String admin, customer;
    static long promoId;
    private static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.4." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> ann(String message, String link, Instant start, Instant end) {
        Map<String, Object> m = new HashMap<>();
        m.put("message", message);
        m.put("link", link);
        m.put("startsAt", start == null ? null : start.toString());
        m.put("endsAt", end == null ? null : end.toString());
        return m;
    }

    @Test @Order(1)
    void public_list_with_defaults_and_cache_header() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Lea", "lastName", "Test",
                "email", "lea@example.cm", "phone", "6 70 00 00 04", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();

        // Seule l'annonce livraison est active par défaut (Mobile Money pas encore opérationnel)
        mvc.perform(get("/api/announcements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("Livraison rapide à Douala, Yaoundé et partout au Cameroun"))
                .andExpect(jsonPath("$[0].link").isEmpty())
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[0].position").doesNotExist());
        send(get("/api/admin/announcements"), admin, null)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].message").value("Payez facilement avec Orange Money et MTN MoMo"))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test @Order(2)
    void admin_access_is_restricted() throws Exception {
        send(get("/api/admin/announcements"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/announcements"), customer, null).andExpect(status().isForbidden());
        send(post("/api/admin/announcements"), customer, ann("Annonce pirate", null, null, null)).andExpect(status().isForbidden());
        send(put("/api/admin/announcements/1"), customer, ann("Annonce pirate", null, null, null)).andExpect(status().isForbidden());
        send(patch("/api/admin/announcements/1/active"), customer, Map.of("active", false)).andExpect(status().isForbidden());
        send(delete("/api/admin/announcements/1"), customer, null).andExpect(status().isForbidden());
        mvc.perform(get("/api/announcements")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test @Order(3)
    void validation_errors() throws Exception {
        send(post("/api/admin/announcements"), admin, ann("", null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.message").exists());
        send(post("/api/admin/announcements"), admin, ann("Ok", null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.message").value("Le message doit contenir entre 3 et 160 caractères"));
        send(post("/api/admin/announcements"), admin, ann("x".repeat(161), null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.message").exists());
        for (String bad : new String[]{"javascript:alert(1)", "catalogue", "//evil.example", "ftp://site.cm", "https://", "/a b"}) {
            send(post("/api/admin/announcements"), admin, ann("Annonce test", bad, null, null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.link").value("Le lien doit être une URL http(s) ou un chemin commençant par /"));
        }
        Instant now = Instant.now();
        send(post("/api/admin/announcements"), admin, ann("Annonce test", null, now, now.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endsAt").value("La date de fin doit être postérieure à la date de début"));
        send(post("/api/admin/announcements"), admin, Map.of("message", "Annonce test", "startsAt", "pas-une-date"))
                .andExpect(status().isBadRequest());
        send(put("/api/admin/announcements/9999"), admin, ann("Annonce test", null, null, null)).andExpect(status().isNotFound());
        send(patch("/api/admin/announcements/9999/active"), admin, Map.of("active", true)).andExpect(status().isNotFound());
        send(patch("/api/admin/announcements/1/active"), admin, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.active").exists());
        send(delete("/api/admin/announcements/9999"), admin, null).andExpect(status().isNotFound());
        send(get("/api/admin/announcements"), admin, null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test @Order(4)
    void public_list_filters_inactive_expired_and_future() throws Exception {
        Instant now = Instant.now();
        JsonNode promo = body(send(post("/api/admin/announcements"), admin,
                ann("  -10 % sur les ordinateurs  ", "/catalogue?cat=ordinateurs", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("-10 % sur les ordinateurs"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()));
        promoId = promo.get("id").asLong();
        send(post("/api/admin/announcements"), admin, ann("Offre expirée", null, now.minus(3, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)))
                .andExpect(status().isCreated());
        send(post("/api/admin/announcements"), admin, ann("Offre à venir", null, now.plus(1, ChronoUnit.DAYS), null))
                .andExpect(status().isCreated());
        Map<String, Object> top = ann("Nouveautés de la semaine", "https://hitech-dz.cm/nouveautes", null, null);
        top.put("position", 0);
        send(post("/api/admin/announcements"), admin, top).andExpect(status().isCreated());

        JsonNode list = body(mvc.perform(get("/api/announcements")).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(3);
        // position 0 (livraison, id 1) puis position 0 (nouveautés, id plus grand), puis la promo
        assertThat(list.get(0).get("message").asText()).startsWith("Livraison rapide");
        assertThat(list.get(1).get("message").asText()).isEqualTo("Nouveautés de la semaine");
        assertThat(list.get(2).get("link").asText()).isEqualTo("/catalogue?cat=ordinateurs");
        assertThat(list.toString()).doesNotContain("Offre expirée", "Offre à venir", "Orange Money");
    }

    @Test @Order(5)
    void update_toggle_and_delete() throws Exception {
        send(patch("/api/admin/announcements/" + promoId + "/active"), admin, Map.of("active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/announcements")).andExpect(jsonPath("$.length()").value(2));

        // PUT complet : active absent = inchangé, dates retirées
        send(put("/api/admin/announcements/" + promoId), admin, ann("-15 % sur les ordinateurs", "/catalogue", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("-15 % sur les ordinateurs"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.endsAt").isEmpty());
        Map<String, Object> on = ann("-15 % sur les ordinateurs", "", null, null);
        on.put("active", true);
        send(put("/api/admin/announcements/" + promoId), admin, on)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.link").isEmpty());
        mvc.perform(get("/api/announcements")).andExpect(jsonPath("$.length()").value(3));

        send(delete("/api/admin/announcements/" + promoId), admin, null).andExpect(status().isNoContent());
        send(delete("/api/admin/announcements/" + promoId), admin, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/announcements")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test @Order(6)
    void empty_list_is_an_array_and_cors_preflight() throws Exception {
        for (JsonNode a : body(send(get("/api/admin/announcements"), admin, null))) {
            send(patch("/api/admin/announcements/" + a.get("id").asLong() + "/active"), admin, Map.of("active", false))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/announcements")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(options("/api/admin/announcements/1/active")
                        .header("Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk());
    }
}
