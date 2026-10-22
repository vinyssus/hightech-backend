package cm.hitechdz.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Le seed de démonstration démarre sans erreur et produit un catalogue cohérent. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.seed.catalog=true"
})
@AutoConfigureMockMvc
class CatalogSeedTest {

    @Autowired MockMvc mvc;

    @Test
    void seed_creates_demo_catalog() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].slug").value("ordinateurs"))
                .andExpect(jsonPath("$[3].slug").value("reseau"))
                .andExpect(jsonPath("$[4].slug").value("electronique"));
        mvc.perform(get("/api/brands"))
                .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(get("/api/products").param("size", "60"))
                .andExpect(jsonPath("$.totalElements").value(12));
        mvc.perform(get("/api/products/samsung-galaxy-a55-5g"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(19))
                .andExpect(jsonPath("$.variants[0].name").value("Couleur"))
                .andExpect(jsonPath("$.combinations.length()").value(4))
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.reviews").value(2));
    }
}
