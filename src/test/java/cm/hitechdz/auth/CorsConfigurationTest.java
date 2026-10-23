package cm.hitechdz.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS pour les domaines du frontend. La variable CORS_ALLOWED_ORIGINS est volontairement
 * « obsolète » ici (localhost + une origine avec « / » final) : les domaines du frontend
 * doivent quand même être acceptés.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cors;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.cors.allowed-origins=http://localhost:8080, https://autre-front.example/ ,*"
})
@AutoConfigureMockMvc
class CorsConfigurationTest {

    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hitech-momo-market.lovable.app",
            "https://id-preview--f6567f8d-b9dd-4b36-8db3-c7395af30cfa.lovable.app",
            "https://f6567f8d-b9dd-4b36-8db3-c7395af30cfa.lovableproject.com",
            "http://localhost:8080",
            "https://autre-front.example"
    })
    void allowed_origins_get_preflight_and_errors(String origin) throws Exception {
        // GET public
        mvc.perform(get("/api/categories").header("Origin", origin))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        // Prérequête sans token, pour une route admin avec Authorization
        mvc.perform(options("/api/admin/products")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("authorization")));

        // Erreurs : en-têtes CORS présents, protection JWT intacte
        mvc.perform(get("/api/admin/products").header("Origin", origin))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        mvc.perform(get("/api/products/inconnu").header("Origin", origin))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        mvc.perform(post("/api/auth/login").header("Origin", origin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.cm\",\"password\":\"Mauvais123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(jsonPath("$.message").value("E-mail ou mot de passe incorrect."));
    }

    @Test
    void other_origins_are_refused_and_wildcard_is_ignored() throws Exception {
        mvc.perform(get("/api/categories").header("Origin", "https://site-pirate.example"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/categories")
                        .header("Origin", "https://autre-projet.lovable.app")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_route_still_requires_admin_role() throws Exception {
        String origin = "https://hitech-momo-market.lovable.app";
        mvc.perform(post("/api/auth/register").header("Origin", origin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Cors\",\"lastName\":\"Client\",\"email\":\"cors@example.cm\","
                        + "\"phone\":\"6 77 00 00 11\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cors@example.cm\",\"password\":\"Secret123\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/admin/products").header("Origin", origin).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }
}
