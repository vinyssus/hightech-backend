package cm.hitechdz.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La marque d'un produit est facultative. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:brandoptional;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
class ProductBrandOptionalTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> product(String name, Object brand, boolean withBrandField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("category", "divers");
        if (withBrandField) m.put("brand", brand);
        m.put("price", 15_000);
        m.put("stock", 3);
        m.put("status", "published");
        return m;
    }

    @Test
    void product_can_be_created_and_updated_without_brand() throws Exception {
        String admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Divers")).andExpect(status().isCreated());

        // Sans champ marque, avec null, avec ""
        JsonNode a = body(send(post("/api/admin/products"), admin, product("Câble HDMI 2 m", null, false))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brand").isEmpty()));
        send(post("/api/admin/products"), admin, product("Multiprise 4 prises", null, true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brand").isEmpty());
        send(post("/api/admin/products"), admin, product("Support TV mural", "  ", true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brand").isEmpty());
        send(post("/api/admin/products"), admin, product("Produit", "X".repeat(61), true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.brand").exists());

        // Public : fiche, liste, facettes et recherche sans erreur
        String id = a.get("id").asText();
        mvc.perform(get("/api/products/" + a.get("slug").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand").isEmpty());
        mvc.perform(get("/api/products").param("cat", "divers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.facets.brands.length()").value(0));
        mvc.perform(get("/api/products").param("q", "hdmi")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/brands")).andExpect(status().isOk());

        // Ajouter une marque, la conserver si le champ est absent, la retirer avec ""
        send(put("/api/admin/products/" + id), admin, product("Câble HDMI 2 m", "Ugreen", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand").value("Ugreen"));
        send(put("/api/admin/products/" + id), admin, product("Câble HDMI 2 m", null, false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand").value("Ugreen"));
        mvc.perform(get("/api/products").param("cat", "divers"))
                .andExpect(jsonPath("$.facets.brands[0].name").value("Ugreen"));
        send(put("/api/admin/products/" + id), admin, product("Câble HDMI 2 m", "", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand").isEmpty());
        send(get("/api/admin/products").param("category", "divers"), admin, null)
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].brand", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }
}
