package cm.hitechdz.auth;

import cm.hitechdz.auth.service.mail.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test de bout en bout des 9 endpoints, sur H2 (mode PostgreSQL) avec les migrations Flyway réelles.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hitech;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final AtomicReference<String> lastCode = new AtomicReference<>();

    @TestConfiguration
    static class MailCapture {
        @Bean @Primary
        MailService capturingMailService() {
            return (to, firstName, code, minutes) -> lastCode.set(code);
        }
    }

    static String access;
    static String refresh;

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test @Order(1)
    void register_validation_and_success_and_conflict() throws Exception {
        send(post("/api/auth/register"), """
                {"firstName":"J","lastName":"Doe","email":"pas-un-mail","phone":"123","password":"faible"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Certains champs sont invalides."))
                .andExpect(jsonPath("$.fieldErrors.firstName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        JsonNode b = body(send(post("/api/auth/register"), """
                {"firstName":"Jean","lastName":"Mbarga","email":"Jean.Mbarga@Example.CM","phone":"6 77 12 34 56","password":"Secret123"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("jean.mbarga@example.cm"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.createdAt").isString()));
        assertThat(b.get("accessToken").asText()).isNotBlank();

        send(post("/api/auth/register"), """
                {"firstName":"Jean","lastName":"Mbarga","email":"jean.mbarga@example.cm","phone":"677123456","password":"Secret123"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Un compte existe déjà avec cette adresse e-mail."));
    }

    @Test @Order(2)
    void login_failure_then_success() throws Exception {
        send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Mauvais123","remember":false}""")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("E-mail ou mot de passe incorrect."));
        send(post("/api/auth/login"), """
                {"email":"inconnu@example.cm","password":"Mauvais123"}""")
                .andExpect(status().isUnauthorized());

        JsonNode b = body(send(post("/api/auth/login"), """
                {"email":"JEAN.MBARGA@example.cm","password":"Secret123","remember":true}""")
                .andExpect(status().isOk()));
        access = b.get("accessToken").asText();
        refresh = b.get("refreshToken").asText();
    }

    @Test @Order(3)
    void me_requires_token() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentification requise."));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Jeton d'authentification invalide."));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jean"));
    }

    @Test @Order(4)
    void refresh_rotates_and_detects_reuse() throws Exception {
        JsonNode b = body(send(post("/api/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}")
                .andExpect(status().isOk()));
        String newRefresh = b.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh);

        // Réutilisation de l'ancien token -> 401 et révocation de toute la famille
        send(post("/api/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}")
                .andExpect(status().isUnauthorized());
        send(post("/api/auth/refresh"), "{\"refreshToken\":\"" + newRefresh + "\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test @Order(5)
    void logout_revokes_refresh_token() throws Exception {
        JsonNode b = body(send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Secret123"}""").andExpect(status().isOk()));
        String rt = b.get("refreshToken").asText();
        access = b.get("accessToken").asText();

        send(post("/api/auth/logout"), "{\"refreshToken\":\"" + rt + "\"}").andExpect(status().isOk());
        send(post("/api/auth/refresh"), "{\"refreshToken\":\"" + rt + "\"}").andExpect(status().isUnauthorized());
    }

    @Test @Order(6)
    void profile_update_and_conflict() throws Exception {
        send(put("/api/auth/profile").header("Authorization", "Bearer " + access), """
                {"firstName":"Jean","lastName":"Mbarga","email":"admin@hitech-dz.cm","phone":"6 77 12 34 56"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cette adresse e-mail est déjà utilisée par un autre compte."));

        send(put("/api/auth/profile").header("Authorization", "Bearer " + access), """
                {"firstName":"Jean-Paul","lastName":"Mbarga","email":"jean.mbarga@example.cm","phone":"+237 699887766"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jean-Paul"))
                .andExpect(jsonPath("$.phone").value("+237 699887766"));
    }

    @Test @Order(7)
    void change_password() throws Exception {
        send(put("/api/auth/password").header("Authorization", "Bearer " + access), """
                {"currentPassword":"Faux12345","newPassword":"Nouveau123"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mot de passe actuel incorrect."));
        send(put("/api/auth/password").header("Authorization", "Bearer " + access), """
                {"currentPassword":"Secret123","newPassword":"Secret123"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le nouveau mot de passe doit être différent de l'actuel."));
        send(put("/api/auth/password").header("Authorization", "Bearer " + access), """
                {"currentPassword":"Secret123","newPassword":"Nouveau123"}""")
                .andExpect(status().isOk());
        send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Nouveau123"}""").andExpect(status().isOk());
    }

    @Test @Order(8)
    void forgot_and_reset_password() throws Exception {
        lastCode.set(null);
        send(post("/api/auth/forgot-password"), "{\"email\":\"nobody@example.cm\"}")
                .andExpect(status().isOk());
        assertThat(lastCode.get()).isNull();

        JsonNode login = body(send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Nouveau123","remember":true}""")
                .andExpect(status().isOk()));
        String rt = login.get("refreshToken").asText();

        send(post("/api/auth/forgot-password"), "{\"email\":\"jean.mbarga@example.cm\"}")
                .andExpect(status().isOk());
        String code = lastCode.get();
        assertThat(code).matches("\\d{6}");

        String wrong = code.equals("000000") ? "111111" : "000000";
        send(post("/api/auth/reset-password"),
                "{\"email\":\"jean.mbarga@example.cm\",\"code\":\"" + wrong + "\",\"password\":\"Reset1234\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Code invalide ou expiré. Demandez un nouveau code."));

        send(post("/api/auth/reset-password"),
                "{\"email\":\"jean.mbarga@example.cm\",\"code\":\"" + code + "\",\"password\":\"Reset1234\"}")
                .andExpect(status().isOk());

        // Code à usage unique
        send(post("/api/auth/reset-password"),
                "{\"email\":\"jean.mbarga@example.cm\",\"code\":\"" + code + "\",\"password\":\"Reset5678\"}")
                .andExpect(status().isBadRequest());
        // Refresh tokens révoqués
        send(post("/api/auth/refresh"), "{\"refreshToken\":\"" + rt + "\"}").andExpect(status().isUnauthorized());
        send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Reset1234"}""").andExpect(status().isOk());
    }

    @Test @Order(9)
    void admin_routes_enforced_server_side() throws Exception {
        JsonNode customer = body(send(post("/api/auth/login"), """
                {"email":"jean.mbarga@example.cm","password":"Reset1234"}""").andExpect(status().isOk()));
        mvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + customer.get("accessToken").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());

        JsonNode admin = body(send(post("/api/auth/login"), """
                {"email":"admin@hitech-dz.cm","password":"Admin123"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN")));
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + admin.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test @Order(10)
    void forgot_password_is_rate_limited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/forgot-password").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.cm\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/forgot-password").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.cm\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value("Trop de tentatives. Veuillez réessayer dans quelques minutes."));
    }

    @Test @Order(11)
    void cors_preflight_allows_frontend() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:8080")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }
}
