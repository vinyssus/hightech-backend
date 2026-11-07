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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sous-catégories : administration, rattachement des produits, filtres et facettes du catalogue. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subcategories;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubcategoryIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static String admin, customer, gamingId, laptopId, phoneId;
    private static final AtomicInteger IP = new AtomicInteger(1);

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.3." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> product(String name, String category, String subcategory, long price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("category", category);
        m.put("brand", "HP");
        m.put("price", price);
        m.put("stock", 5);
        m.put("status", "published");
        if (subcategory != null) m.put("subcategory", subcategory);
        return m;
    }

    @Test @Order(1)
    void setup_and_public_category_format_is_backward_compatible() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Paul", "lastName", "Test",
                "email", "paul@example.cm", "phone", "6 70 00 00 03", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();

        send(post("/api/admin/categories"), admin, Map.of("name", "Ordinateurs", "icon", "laptop",
                "sub", List.of("Portables", "Gaming", "gaming", " ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sub.length()").value(2))
                .andExpect(jsonPath("$.subcategories[1].slug").value("gaming"))
                .andExpect(jsonPath("$.subcategories[1].productCount").value(0));
        send(post("/api/admin/categories"), admin, Map.of("name", "Smartphones", "icon", "smartphone",
                "sub", List.of("Android"))).andExpect(status().isCreated());

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sub[0]").value("Portables"))           // ancien format conservé
                .andExpect(jsonPath("$[0].sub[1]").value("Gaming"))
                .andExpect(jsonPath("$[0].subcategories[0].slug").value("portables"))
                .andExpect(jsonPath("$[0].subcategories[0].name").value("Portables"))
                .andExpect(jsonPath("$[0].subcategories[0].productCount").doesNotExist())
                .andExpect(jsonPath("$[1].subcategories[0].slug").value("android"));
    }

    @Test @Order(2)
    void admin_subcategory_crud_rules() throws Exception {
        send(post("/api/admin/categories/ordinateurs/subcategories"), admin, Map.of("name", "PC de bureau"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("pc-de-bureau"))
                .andExpect(jsonPath("$.position").value(2));
        send(post("/api/admin/categories/ordinateurs/subcategories"), admin, Map.of("name", "PORTABLES"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors.name").value("Cette sous-catégorie existe déjà dans cette catégorie."));
        send(post("/api/admin/categories/ordinateurs/subcategories"), admin, Map.of("name", "&&&"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
        send(post("/api/admin/categories/ordinateurs/subcategories"), admin, Map.of("name", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
        send(post("/api/admin/categories/inconnue/subcategories"), admin, Map.of("name", "X"))
                .andExpect(status().isNotFound());
        send(post("/api/admin/categories/ordinateurs/subcategories"), customer, Map.of("name", "Pirate"))
                .andExpect(status().isForbidden());
        send(post("/api/admin/categories/ordinateurs/subcategories"), null, Map.of("name", "Pirate"))
                .andExpect(status().isUnauthorized());
        // Le même nom est permis dans une autre catégorie
        send(post("/api/admin/categories/smartphones/subcategories"), admin, Map.of("name", "Gaming"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("gaming"));
    }

    @Test @Order(3)
    void products_are_attached_and_validated() throws Exception {
        JsonNode g = body(send(post("/api/admin/products"), admin, product("PC Portable Gamer Omen", "ordinateurs", "gaming", 900_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("ordinateurs"))
                .andExpect(jsonPath("$.subcategory").value("gaming")));
        gamingId = g.get("id").asText();
        // Le nom est accepté comme le slug
        laptopId = body(send(post("/api/admin/products"), admin, product("HP ProBook 450", "ordinateurs", "Portables", 500_000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subcategory").value("portables"))).get("id").asText();
        phoneId = body(send(post("/api/admin/products"), admin, product("Tecno Spark 20", "smartphones", null, 90_000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subcategory").isEmpty())).get("id").asText();

        send(post("/api/admin/products"), admin, product("Produit Faux", "smartphones", "portables", 10_000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.subcategory").value("Cette sous-catégorie n'existe pas dans la catégorie choisie."));
        send(post("/api/admin/products"), admin, product("Produit Faux", "ordinateurs", "tablettes", 10_000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.subcategory").exists());

        String slug = g.get("slug").asText();
        mvc.perform(get("/api/products/" + slug)).andExpect(jsonPath("$.subcategory").value("gaming"));
        send(get("/api/admin/products").param("subcategory", "gaming").param("category", "ordinateurs"), admin, null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(gamingId));
    }

    @Test @Order(4)
    void public_filter_and_facets() throws Exception {
        mvc.perform(get("/api/products").param("cat", "ordinateurs").param("sub", "gaming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subcategory").value("gaming"))
                // facettes calculées sans le filtre sous-catégorie
                .andExpect(jsonPath("$.facets.subcategories.length()").value(2))
                .andExpect(jsonPath("$.facets.subcategories[0].slug").value("portables"))
                .andExpect(jsonPath("$.facets.subcategories[0].name").value("Portables"))
                .andExpect(jsonPath("$.facets.subcategories[0].count").value(1))
                .andExpect(jsonPath("$.facets.subcategories[1].slug").value("gaming"));
        mvc.perform(get("/api/products").param("cat", "ordinateurs"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.facets.subcategories.length()").value(0));
        mvc.perform(get("/api/products").param("cat", "smartphones").param("sub", "gaming"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/products").param("q", "gaming"))
                .andExpect(jsonPath("$.content[?(@.id=='" + gamingId + "')]").exists());
    }

    @Test @Order(5)
    void product_update_keeps_or_clears_subcategory() throws Exception {
        // Ancien formulaire (sans le champ) : la sous-catégorie est conservée
        send(put("/api/admin/products/" + gamingId), admin, product("PC Portable Gamer Omen", "ordinateurs", null, 900_000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subcategory").value("gaming"));
        // Chaîne vide : retirée
        send(put("/api/admin/products/" + laptopId), admin, product("HP ProBook 450", "ordinateurs", "", 500_000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subcategory").isEmpty());
        // Changement de catégorie sans le champ : retirée (n'appartient plus à la catégorie)
        send(put("/api/admin/products/" + laptopId), admin, product("HP ProBook 450", "smartphones", null, 500_000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subcategory").isEmpty());
        send(put("/api/admin/products/" + laptopId), admin, product("HP ProBook 450", "ordinateurs", "portables", 500_000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subcategory").value("portables"));
    }

    @Test @Order(6)
    void rename_keeps_slug_and_reorder() throws Exception {
        send(put("/api/admin/categories/ordinateurs/subcategories/gaming"), admin, Map.of("name", "Jeux vidéo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("gaming"))
                .andExpect(jsonPath("$.name").value("Jeux vidéo"))
                .andExpect(jsonPath("$.productCount").value(1));
        send(put("/api/admin/categories/ordinateurs/subcategories/gaming"), admin, Map.of("name", "portables"))
                .andExpect(status().isConflict());
        send(put("/api/admin/categories/ordinateurs/subcategories/inconnue"), admin, Map.of("name", "X"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/products").param("q", "jeux video"))
                .andExpect(jsonPath("$.totalElements").value(1));

        send(put("/api/admin/categories/ordinateurs/subcategories"), admin,
                Map.of("slugs", List.of("pc-de-bureau", "gaming", "portables")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subcategories[0].slug").value("pc-de-bureau"))
                .andExpect(jsonPath("$.subcategories[1].productCount").value(1))
                .andExpect(jsonPath("$.sub[1]").value("Jeux vidéo"));
        send(put("/api/admin/categories/ordinateurs/subcategories"), admin, Map.of("slugs", List.of("gaming")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.slugs").exists());
        mvc.perform(get("/api/categories"))
                .andExpect(jsonPath("$[0].subcategories[2].slug").value("portables"));

        // Renommer la catégorie avec seulement le nom ne touche pas aux sous-catégories
        send(put("/api/admin/categories/ordinateurs"), admin, Map.of("name", "Ordinateurs & PC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subcategories.length()").value(3));
    }

    @Test @Order(7)
    void delete_rules() throws Exception {
        send(delete("/api/admin/categories/ordinateurs/subcategories/gaming"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("1 produit(s)")));
        // Ancien format : retirer de « sub » une sous-catégorie utilisée est aussi refusé
        send(put("/api/admin/categories/ordinateurs"), admin, Map.of("name", "Ordinateurs & PC",
                "sub", List.of("PC de bureau", "Portables")))
                .andExpect(status().isConflict());
        send(get("/api/admin/categories"), admin, null).andExpect(jsonPath("$[0].subcategories.length()").value(3));

        send(delete("/api/admin/categories/ordinateurs/subcategories/pc-de-bureau"), admin, null)
                .andExpect(status().isNoContent());
        send(delete("/api/admin/categories/ordinateurs/subcategories/pc-de-bureau"), admin, null)
                .andExpect(status().isNotFound());
        send(delete("/api/admin/categories/ordinateurs/subcategories/gaming"), customer, null)
                .andExpect(status().isForbidden());

        // Produit supprimé (logiquement) : la sous-catégorie peut alors être supprimée
        send(delete("/api/admin/products/" + gamingId), admin, null).andExpect(status().isNoContent());
        send(delete("/api/admin/categories/ordinateurs/subcategories/gaming"), admin, null)
                .andExpect(status().isNoContent());
        JsonNode cats = body(mvc.perform(get("/api/categories")));
        assertThat(cats.get(0).get("sub").toString()).isEqualTo("[\"Portables\"]");
        assertThat(cats.get(0).get("subcategories").get(0).get("slug").asText()).isEqualTo("portables");
    }
}
