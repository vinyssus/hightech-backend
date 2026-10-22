package cm.hitechdz.auth;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.InventoryService.ReserveItem;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.StoredImage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module catalogue de bout en bout (H2 en mode PostgreSQL, migrations Flyway réelles, Cloudinary mocké).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InventoryService inventory;
    @Autowired ProductRepository productRepository;

    @MockitoBean ImageStorageService storage;

    static String admin;
    static String customer;
    static UUID customerId;
    static String customer2;
    static UUID customer2Id;

    static String hpId, hpSlug, jblId, galaxyId, galaxySlug, hp2Id, hp2Slug, draftId, draftSlug, lastUnitId, routerId;
    static String routerPublicId;

    private static final AtomicInteger UPLOADS = new AtomicInteger();

    @BeforeEach
    void mockStorage() {
        when(storage.upload(any(), anyString())).thenAnswer(inv -> {
            String folder = inv.getArgument(1);
            int n = UPLOADS.incrementAndGet();
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/img" + n + ".png",
                    folder + "/img" + n, 800, 600);
        });
        when(storage.move(anyString(), anyString())).thenAnswer(inv -> {
            String publicId = inv.getArgument(0);
            String folder = inv.getArgument(1);
            String target = folder + "/" + publicId.substring(publicId.lastIndexOf('/') + 1);
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v2/" + target + ".png", target, 800, 600);
        });
    }

    // ------------------------------------------------------------------ helpers
    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> product(String name, String brand, String category, long price, Long oldPrice, int stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", "Description de " + name);
        m.put("category", category);
        m.put("brand", brand);
        m.put("price", price);
        m.put("oldPrice", oldPrice);
        m.put("stock", stock);
        m.put("images", List.of());
        m.put("specs", List.of(Map.of("key", "Garantie", "value", "12 mois")));
        m.put("status", "published");
        return m;
    }

    private JsonNode createProduct(Map<String, Object> body) throws Exception {
        return body(send(post("/api/admin/products"), admin, body).andExpect(status().isCreated()));
    }

    private static byte[] png(int size) {
        byte[] b = new byte[size];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    // ------------------------------------------------------------------ tests
    @Test @Order(1)
    void setup_accounts() throws Exception {
        admin = body(send(post("/api/auth/login"), null,
                Map.of("email", "admin@hitech-dz.cm", "password", "Admin123")).andExpect(status().isOk()))
                .get("accessToken").asText();
        JsonNode c1 = body(send(post("/api/auth/register"), null, Map.of("firstName", "Paul", "lastName", "Etoa",
                "email", "paul@example.cm", "phone", "6 99 00 00 01", "password", "Secret123")).andExpect(status().isCreated()));
        customer = c1.get("accessToken").asText();
        customerId = UUID.fromString(c1.get("user").get("id").asText());
        JsonNode c2 = body(send(post("/api/auth/register"), null, Map.of("firstName", "Aïcha", "lastName", "Bello",
                "email", "aicha@example.cm", "phone", "6 99 00 00 02", "password", "Secret123")).andExpect(status().isCreated()));
        customer2 = c2.get("accessToken").asText();
        customer2Id = UUID.fromString(c2.get("user").get("id").asText());
    }

    @Test @Order(2)
    void categories_slugs_and_conflicts() throws Exception {
        send(post("/api/admin/categories"), admin, Map.of("name", "Ordinateurs", "icon", "laptop",
                "sub", List.of("Portables", "Gaming")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("ordinateurs"))
                .andExpect(jsonPath("$.productCount").value(0));
        send(post("/api/admin/categories"), admin, Map.of("name", "Smartphones", "icon", "smartphone"))
                .andExpect(status().isCreated());
        send(post("/api/admin/categories"), admin, Map.of("name", "Écouteurs Sans-Fil & Audio", "icon", "headphones"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("ecouteurs-sans-fil-audio"));

        send(post("/api/admin/categories"), admin, Map.of("name", "ORDINATEURS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cette catégorie existe déjà."));
        send(post("/api/admin/categories"), admin, Map.of("name", "&&&"))
                .andExpect(status().isBadRequest());
        send(post("/api/admin/categories"), admin, Map.of("name", "Divers", "icon", "fusee"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.icon").exists());

        // Renommer ne change pas le slug
        send(put("/api/admin/categories/ecouteurs-sans-fil-audio"), admin, Map.of("name", "Audio & Accessoires"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ecouteurs-sans-fil-audio"))
                .andExpect(jsonPath("$.name").value("Audio & Accessoires"));
        send(put("/api/admin/categories/inconnue"), admin, Map.of("name", "X"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].icon").value("laptop"))
                .andExpect(jsonPath("$[0].sub[1]").value("Gaming"))
                .andExpect(jsonPath("$[2].icon").value("headphones"));
    }

    @Test @Order(3)
    void customer_is_forbidden_and_anonymous_unauthorized() throws Exception {
        send(post("/api/admin/products"), customer, product("Test", "HP", "ordinateurs", 1000, null, 1))
                .andExpect(status().isForbidden());
        send(post("/api/admin/categories"), customer, Map.of("name", "Pirate"))
                .andExpect(status().isForbidden());
        send(get("/api/admin/products"), null, null).andExpect(status().isUnauthorized());
    }

    @Test @Order(4)
    void product_validation() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("name", "ab");
        bad.put("price", 0);
        bad.put("brand", "");
        send(post("/api/admin/products"), admin, bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.price").exists())
                .andExpect(jsonPath("$.fieldErrors.brand").exists())
                .andExpect(jsonPath("$.fieldErrors.category").exists())
                .andExpect(jsonPath("$.fieldErrors.stock").exists());

        send(post("/api/admin/products"), admin, product("Produit test", "HP", "inexistante", 1000, null, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.category").value("Cette catégorie n'existe pas."));
        send(post("/api/admin/products"), admin, product("Produit test", "HP", "ordinateurs", 1000, 900L, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.oldPrice").value("L'ancien prix doit être supérieur au prix."));

        Map<String, Object> httpImage = product("Produit test", "HP", "ordinateurs", 1000, null, 1);
        httpImage.put("images", List.of("http://exemple.cm/a.png"));
        send(post("/api/admin/products"), admin, httpImage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.images").exists());

        Map<String, Object> tooMany = product("Produit test", "HP", "ordinateurs", 1000, null, 1);
        tooMany.put("images", List.of("https://a/1", "https://a/2", "https://a/3", "https://a/4", "https://a/5",
                "https://a/6", "https://a/7", "https://a/8", "https://a/9"));
        send(post("/api/admin/products"), admin, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.images").exists());

        mvc.perform(post("/api/admin/products").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Produit test\",\"category\":\"ordinateurs\",\"brand\":\"HP\",\"price\":1000,\"stock\":2.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(5)
    void create_products_with_slugs_and_brands() throws Exception {
        Map<String, Object> hp = product("HP Pavilion 15", "HP", "ordinateurs", 400_000, 450_000L, 10);
        hp.put("images", List.of("https://cdn.exemple.cm/hp.png"));   // URL externe acceptée (liste de String)
        JsonNode p1 = createProduct(hp);
        hpId = p1.get("id").asText();
        hpSlug = p1.get("slug").asText();
        assertThat(hpSlug).isEqualTo("hp-pavilion-15");
        assertThat(p1.get("isNew").asBoolean()).isTrue();
        assertThat(p1.get("image").asText()).isEqualTo("https://cdn.exemple.cm/hp.png");
        assertThat(p1.get("status").asText()).isEqualTo("published");

        JsonNode p2 = createProduct(product("Écouteurs Bluetooth JBL", "JBL", "ecouteurs-sans-fil-audio", 30_000, null, 0));
        jblId = p2.get("id").asText();

        JsonNode p3 = createProduct(product("Galaxy A55", "Samsung", "smartphones", 280_000, null, 5));
        galaxyId = p3.get("id").asText();
        galaxySlug = p3.get("slug").asText();

        // Même nom : suffixe -2 ; « hp » désigne la marque existante « HP »
        JsonNode p4 = createProduct(product("HP Pavilion 15", "hp", "Ordinateurs", 410_000, null, 10));
        hp2Id = p4.get("id").asText();
        hp2Slug = p4.get("slug").asText();
        assertThat(hp2Slug).isEqualTo("hp-pavilion-15-2");
        assertThat(p4.get("brand").asText()).isEqualTo("HP");

        Map<String, Object> draft = product("Produit Brouillon", "Dell", "ordinateurs", 500_000, null, 3);
        draft.put("status", "draft");
        JsonNode p5 = createProduct(draft);
        draftId = p5.get("id").asText();
        draftSlug = p5.get("slug").asText();

        send(get("/api/admin/brands"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.name=='HP')].productCount").value(2));

        // Le slug est régénéré seulement si le nom change
        Map<String, Object> rename = product("HP Pavilion 15", "HP", "ordinateurs", 400_000, 450_000L, 10);
        rename.remove("images");
        send(put("/api/admin/products/" + hpId), admin, rename)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("hp-pavilion-15"))
                .andExpect(jsonPath("$.images[0]").value("https://cdn.exemple.cm/hp.png"));
    }

    @Test @Order(6)
    void public_listing_filters_sort_pagination_and_facets() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))      // le brouillon est exclu
                .andExpect(jsonPath("$.size").value(12))
                .andExpect(jsonPath("$.facets.priceMin").value(30_000))
                .andExpect(jsonPath("$.facets.priceMax").value(410_000));

        // Recherche sans accents ni casse, sur nom, marque et catégorie
        mvc.perform(get("/api/products").param("q", "ECOUTEUR"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(jblId));
        mvc.perform(get("/api/products").param("q", "samsung"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/products").param("q", "audio"))      // nom de catégorie
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/products").param("cat", "smartphones"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Marques : séparées par des virgules ou répétées ; facettes calculées sans le filtre marque
        mvc.perform(get("/api/products").param("brands", "HP,Samsung"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.facets.brands[?(@.name=='JBL')].count").value(1))
                .andExpect(jsonPath("$.facets.brands[?(@.name=='HP')].count").value(2));
        mvc.perform(get("/api/products").param("brands", "hp").param("brands", "JBL"))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get("/api/products").param("min", "100000").param("max", "400000"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/products").param("promo", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(hpId));
        mvc.perform(get("/api/products").param("inStock", "true"))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get("/api/products").param("sort", "prix-asc"))
                .andExpect(jsonPath("$.content[0].price").value(30_000))
                .andExpect(jsonPath("$.content[3].price").value(410_000));
        mvc.perform(get("/api/products").param("sort", "prix-desc"))
                .andExpect(jsonPath("$.content[0].price").value(410_000));
        mvc.perform(get("/api/products").param("sort", "nouveautes"))
                .andExpect(jsonPath("$.content[0].id").value(hp2Id));
        mvc.perform(get("/api/products").param("sort", "n-importe-quoi"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/products").param("size", "3").param("page", "1").param("sort", "prix-asc"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Suggestions
        mvc.perform(get("/api/products/search").param("q", "pavil").param("limit", "6"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test @Order(7)
    void brand_counts_follow_other_filters() throws Exception {
        mvc.perform(get("/api/brands").param("cat", "ordinateurs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='HP')].productCount").value(2))
                .andExpect(jsonPath("$[?(@.name=='Samsung')].productCount").value(0))
                .andExpect(jsonPath("$[?(@.name=='Dell')].productCount").value(0));   // brouillon non compté
        mvc.perform(get("/api/brands").param("inStock", "true"))
                .andExpect(jsonPath("$[?(@.name=='JBL')].productCount").value(0));
    }

    @Test @Order(8)
    void product_detail_by_slug() throws Exception {
        mvc.perform(get("/api/products/" + hpSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HP Pavilion 15"))
                .andExpect(jsonPath("$.specs[0].key").value("Garantie"))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.status").doesNotExist());
        mvc.perform(get("/api/products/" + draftSlug))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Produit introuvable."));
        mvc.perform(get("/api/products/inconnu")).andExpect(status().isNotFound());

        send(get("/api/admin/products/slug/" + draftSlug), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("draft"));
        send(get("/api/admin/products").param("status", "draft"), admin, null)
                .andExpect(jsonPath("$.totalElements").value(1));
        send(get("/api/admin/products").param("search", "pavilion").param("size", "1"), admin, null)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test @Order(9)
    void delete_category_or_brand_in_use_is_refused() throws Exception {
        send(delete("/api/admin/categories/ordinateurs"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Impossible de supprimer : 3 produit(s) utilisent cette catégorie."));
        send(post("/api/admin/categories"), admin, Map.of("name", "Temporaire")).andExpect(status().isCreated());
        send(delete("/api/admin/categories/temporaire"), admin, null).andExpect(status().isNoContent());

        send(delete("/api/admin/brands/hp"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Impossible de supprimer : 2 produit(s) utilisent cette marque."));
        send(post("/api/admin/brands"), admin, Map.of("name", "Sony")).andExpect(status().isCreated());
        send(post("/api/admin/brands"), admin, Map.of("name", "SONY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cette marque existe déjà."));
        send(put("/api/admin/brands/sony"), admin, Map.of("name", "Sony Corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sony"))
                .andExpect(jsonPath("$.name").value("Sony Corp"));
        send(delete("/api/admin/brands/sony"), admin, null).andExpect(status().isNoContent());
    }

    @Test @Order(10)
    void upload_validation_and_tmp_images() throws Exception {
        clearInvocations(storage);
        MockMultipartFile text = new MockMultipartFile("files", "notes.png", "image/png",
                "pas une image".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/admin/uploads/images").file(text).header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Format non accepté : seules les images JPEG, PNG ou WebP sont autorisées."));

        MockMultipartFile huge = new MockMultipartFile("files", "big.png", "image/png", png(6 * 1024 * 1024));
        mvc.perform(multipart("/api/admin/uploads/images").file(huge).header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Fichier trop volumineux : 5 Mo maximum par image."));
        verify(storage, never()).upload(any(), anyString());

        MockMultipartFile ok = new MockMultipartFile("files", "routeur.png", "image/png", png(2048));
        JsonNode uploaded = body(mvc.perform(multipart("/api/admin/uploads/images").file(ok)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isCreated()));
        String tmpPublicId = uploaded.get(0).get("publicId").asText();
        assertThat(tmpPublicId).startsWith("hitech-dz/products/tmp/");

        // Création avec l'image tmp : déplacée dans hitech-dz/products/{slug}
        Map<String, Object> router = product("Routeur Wi-Fi 6 TP-Link", "TP-Link", "ordinateurs", 55_000, 60_000L, 4);
        router.put("images", List.of(Map.of("url", uploaded.get(0).get("url").asText(), "publicId", tmpPublicId)));
        JsonNode created = createProduct(router);
        routerId = created.get("id").asText();
        routerPublicId = created.get("imageDetails").get(0).get("publicId").asText();
        assertThat(routerPublicId).startsWith("hitech-dz/products/routeur-wi-fi-6-tp-link/");
        assertThat(created.get("image").asText()).contains("/image/upload/f_auto,q_auto/");
        verify(storage).move(tmpPublicId, "hitech-dz/products/routeur-wi-fi-6-tp-link");

        // Un publicId étranger est refusé
        Map<String, Object> foreign = product("Autre routeur", "TP-Link", "ordinateurs", 55_000, null, 4);
        foreign.put("images", List.of(Map.of("url", "https://res.cloudinary.com/x.png", "publicId", "autre/dossier/x")));
        send(post("/api/admin/products"), admin, foreign).andExpect(status().isBadRequest());

        // Ajout direct de deux images, réordonnancement, suppression via publicId encodé
        MockMultipartFile a = new MockMultipartFile("files", "a.png", "image/png", png(1024));
        MockMultipartFile b = new MockMultipartFile("files", "b.png", "image/png", png(1024));
        JsonNode withImages = body(mvc.perform(multipart("/api/admin/products/" + routerId + "/images").file(a).file(b)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(3)));
        String second = withImages.get("imageDetails").get(1).get("publicId").asText();
        String third = withImages.get("imageDetails").get(2).get("publicId").asText();

        send(put("/api/admin/products/" + routerId + "/images/order"), admin,
                Map.of("publicIds", List.of(third, routerPublicId, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageDetails[0].publicId").value(third))
                .andExpect(jsonPath("$.imageDetails[0].position").value(0));
        send(put("/api/admin/products/" + routerId + "/images/order"), admin, Map.of("publicIds", List.of(third)))
                .andExpect(status().isBadRequest());

        clearInvocations(storage);
        String encoded = URLEncoder.encode(second, StandardCharsets.UTF_8);
        mvc.perform(delete(URI.create("/api/admin/products/" + routerId + "/images/" + encoded))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(2));
        verify(storage).delete(second);

        // Mise à jour qui retire une image : supprimée de Cloudinary après le commit
        clearInvocations(storage);
        Map<String, Object> update = product("Routeur Wi-Fi 6 TP-Link", "TP-Link", "ordinateurs", 55_000, 60_000L, 4);
        update.put("images", List.of(Map.of("url", "https://ignored", "publicId", routerPublicId)));
        send(put("/api/admin/products/" + routerId), admin, update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1));
        verify(storage).delete(third);
    }

    @Test @Order(11)
    void variants_drive_stock_and_detail() throws Exception {
        List<Map<String, Object>> combos = new ArrayList<>();
        combos.add(variant("A55-N-128", "Noir", "#000000", "128 Go", 0, 2));
        combos.add(variant("A55-N-256", "Noir", "#000000", "256 Go", 30_000, 0));
        combos.add(variant("A55-B-128", "Bleu", "#1E40AF", "128 Go", 0, 1));
        send(put("/api/admin/products/" + galaxyId + "/variants"), admin, Map.of("combinations", combos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(3))
                .andExpect(jsonPath("$.combinations.length()").value(3));

        mvc.perform(get("/api/products/" + galaxySlug))
                .andExpect(jsonPath("$.stock").value(3))
                .andExpect(jsonPath("$.variants[0].name").value("Couleur"))
                .andExpect(jsonPath("$.variants[0].options[0].label").value("Noir"))
                .andExpect(jsonPath("$.variants[0].options[0].hex").value("#000000"))
                .andExpect(jsonPath("$.variants[1].name").value("Capacité"))
                .andExpect(jsonPath("$.variants[1].options[1].label").value("256 Go"))
                .andExpect(jsonPath("$.variants[1].options[1].delta").value(30_000))
                .andExpect(jsonPath("$.variants[1].options[1].available").value(false))
                .andExpect(jsonPath("$.combinations[1].price").value(310_000));

        List<Map<String, Object>> dup = List.of(variant("X-1", "Noir", null, null, 0, 1), variant("X-1", "Bleu", null, null, 0, 1));
        send(put("/api/admin/products/" + galaxyId + "/variants"), admin, Map.of("combinations", dup))
                .andExpect(status().isBadRequest());
        send(put("/api/admin/products/" + galaxyId + "/variants"), admin,
                Map.of("combinations", List.of(variant("X-2", "Noir", null, null, 0, -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['combinations[0].stock']").exists());
    }

    private static Map<String, Object> variant(String sku, String color, String hex, String capacity, long delta, int stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", sku);
        m.put("color", color);
        m.put("colorHex", hex);
        m.put("capacity", capacity);
        m.put("priceDelta", delta);
        m.put("stock", stock);
        return m;
    }

    @Test @Order(12)
    void cart_validate_recomputes_prices_server_side() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("productId", hpId, "qty", 2, "price", 390_000));            // prix obsolète
        items.add(Map.of("productId", jblId, "qty", 1));                              // stock 0
        items.add(Map.of("productId", draftId, "qty", 1));                            // brouillon
        items.add(Map.of("productId", galaxyId, "sku", "A55-B-128", "qty", 3));       // 1 en stock
        items.add(Map.of("productId", galaxyId, "qty", 1));                           // option manquante
        items.add(Map.of("productId", hp2Id, "qty", 15));                             // max 10
        send(post("/api/cart/validate"), null, Map.of("items", items))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(400_000))
                .andExpect(jsonPath("$.items[0].oldPrice").value(450_000))
                .andExpect(jsonPath("$.items[0].message").value("Le prix a changé"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[1].available").value(false))
                .andExpect(jsonPath("$.items[1].message").value("Produit indisponible"))
                .andExpect(jsonPath("$.items[2].available").value(false))
                .andExpect(jsonPath("$.items[3].qty").value(1))
                .andExpect(jsonPath("$.items[3].unitPrice").value(280_000))
                .andExpect(jsonPath("$.items[3].message").value("Plus que 1 en stock"))
                .andExpect(jsonPath("$.items[4].available").value(false))
                .andExpect(jsonPath("$.items[4].message").value("Veuillez choisir une option"))
                .andExpect(jsonPath("$.items[5].qty").value(10))
                .andExpect(jsonPath("$.items[5].message").value("Quantité maximale : 10 par article"))
                .andExpect(jsonPath("$.subtotal").value(2 * 400_000 + 280_000 + 10 * 410_000));
    }

    @Test @Order(13)
    void concurrent_reservations_sell_the_last_unit_once() throws Exception {
        lastUnitId = createProduct(product("Dernière unité", "HP", "ordinateurs", 99_000, null, 1)).get("id").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> buy = () -> {
            start.await();
            try {
                inventory.reserve(customerId, List.of(new ReserveItem(lastUnitId, null, 1)));
                return true;
            } catch (ApiException e) {
                assertThat(e.getStatus().value()).isEqualTo(409);
                return false;
            }
        };
        Future<Boolean> a = pool.submit(buy);
        Future<Boolean> b = pool.submit(buy);
        start.countDown();
        int successes = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(productRepository.findById(lastUnitId).orElseThrow().getStock()).isZero();

        ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> inventory.reserve(customerId, List.of(new ReserveItem(lastUnitId, null, 1))));
        assertThat(e.getFieldErrors()).containsEntry("items[0]", "Produit indisponible");
    }

    @Test @Order(14)
    void failed_payment_restocks_and_confirmed_payment_increments_sold() {
        CustomerOrder failed = inventory.reserve(customerId, List.of(new ReserveItem(hpId, null, 3)));
        assertThat(productRepository.findById(hpId).orElseThrow().getStock()).isEqualTo(7);
        inventory.failPayment(failed.getId());
        assertThat(productRepository.findById(hpId).orElseThrow().getStock()).isEqualTo(10);

        CustomerOrder variantOrder = inventory.reserve(customer2Id, List.of(new ReserveItem(galaxyId, "A55-N-128", 1)));
        assertThat(productRepository.findById(galaxyId).orElseThrow().getStock()).isEqualTo(2);
        assertThat(variantOrder.getItems().get(0).getVariantLabel()).isEqualTo("Noir · 128 Go");
        inventory.confirmPayment(variantOrder.getId());
        inventory.markDelivered(variantOrder.getId());          // achat vérifié pour customer2

        CustomerOrder paid = inventory.reserve(customerId, List.of(new ReserveItem(hpId, null, 2)));
        inventory.confirmPayment(paid.getId());
        assertThat(productRepository.findById(hpId).orElseThrow().getSold()).isEqualTo(2);
        assertThat(productRepository.findById(hpId).orElseThrow().getStock()).isEqualTo(8);

        // Annulation après paiement : stock remis et ventes décomptées
        CustomerOrder cancelled = inventory.reserve(customerId, List.of(new ReserveItem(hpId, null, 1)));
        inventory.confirmPayment(cancelled.getId());
        inventory.cancel(cancelled.getId());
        assertThat(productRepository.findById(hpId).orElseThrow().getSold()).isEqualTo(2);
        assertThat(productRepository.findById(hpId).orElseThrow().getStock()).isEqualTo(8);
    }

    @Test @Order(15)
    void best_sellers_flash_and_new() throws Exception {
        mvc.perform(get("/api/products/best-sellers"))
                .andExpect(jsonPath("$[0].id").value(hpId));
        mvc.perform(get("/api/products/flash"))
                .andExpect(jsonPath("$[*].oldPrice").isNotEmpty());
        mvc.perform(get("/api/products").param("sort", "popularite"))
                .andExpect(jsonPath("$.content[0].id").value(hpId));
        mvc.perform(get("/api/products/new"))
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test @Order(16)
    void reviews_rating_and_verified_purchase() throws Exception {
        send(post("/api/products/" + hpSlug + "/reviews"), null,
                Map.of("rating", 4, "title", "Bien", "body", "Très bon ordinateur portable."))
                .andExpect(status().isUnauthorized());
        send(post("/api/products/" + hpSlug + "/reviews"), customer,
                Map.of("rating", 6, "title", "ab", "body", "court"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rating").exists())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.body").exists());

        JsonNode r1 = body(send(post("/api/products/" + hpSlug + "/reviews"), customer,
                Map.of("rating", 4, "title", "Bon achat", "body", "Très bon ordinateur portable, rapide.", "city", "Douala"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("Paul"))
                .andExpect(jsonPath("$.city").value("Douala"))
                .andExpect(jsonPath("$.verified").value(false)));
        send(post("/api/products/" + hpSlug + "/reviews"), customer,
                Map.of("rating", 5, "title", "Encore", "body", "Je donne un deuxième avis."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vous avez déjà donné votre avis sur ce produit."));

        send(post("/api/products/" + hpSlug + "/reviews"), customer2,
                Map.of("rating", 5, "title", "Parfait", "body", "Rien à redire, je recommande vivement."))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/products/" + hpSlug))
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.reviews").value(2));

        // Achat livré (test précédent) : avis vérifié
        send(post("/api/products/" + galaxySlug + "/reviews"), customer2,
                Map.of("rating", 5, "title", "Super téléphone", "body", "Écran magnifique et bonne autonomie."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(true));

        mvc.perform(get("/api/products/" + hpSlug + "/reviews").param("sort", "rating"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(jsonPath("$.summary.average").value(4.5))
                .andExpect(jsonPath("$.summary.count").value(2))
                .andExpect(jsonPath("$.summary.distribution['5']").value(1))
                .andExpect(jsonPath("$.summary.distribution['4']").value(1))
                .andExpect(jsonPath("$.summary.distribution['1']").value(0));

        mvc.perform(get("/api/products").param("rating", "4.5"))
                .andExpect(jsonPath("$.totalElements").value(2));   // HP (4.5) et Galaxy (5.0)

        long r1Id = r1.get("id").asLong();
        send(post("/api/reviews/" + r1Id + "/helpful"), customer2, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.helpfulCount").value(1));
        send(post("/api/reviews/" + r1Id + "/helpful"), customer2, null)
                .andExpect(status().isConflict());

        // Modération : la note est recalculée
        send(get("/api/admin/reviews").param("productId", hpId), admin, null)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].productSlug").value(hpSlug));
        send(delete("/api/admin/reviews/" + r1Id), admin, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/products/" + hpSlug))
                .andExpect(jsonPath("$.rating").value(5.0))
                .andExpect(jsonPath("$.reviews").value(1));
    }

    @Test @Order(17)
    void favorites() throws Exception {
        send(put("/api/me/favorites/" + hpId), customer, null).andExpect(status().isNoContent());
        send(put("/api/me/favorites/" + hpId), customer, null).andExpect(status().isNoContent());   // idempotent
        send(get("/api/me/favorites"), customer, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(hpId));
        send(put("/api/me/favorites/inconnu"), customer, null).andExpect(status().isNotFound());
        send(get("/api/me/favorites"), null, null).andExpect(status().isUnauthorized());

        send(post("/api/me/favorites/merge"), customer, Map.of("productIds", List.of(jblId, "inconnu", draftId, hpId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        send(delete("/api/me/favorites/" + hpId), customer, null).andExpect(status().isNoContent());
        send(get("/api/me/favorites"), customer, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(jblId));
    }

    @Test @Order(18)
    void low_stock_alerts() throws Exception {
        send(get("/api/admin/products/low-stock"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[?(@.id=='" + galaxyId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + hpId + "')]").doesNotExist());
    }

    @Test @Order(19)
    void delete_product_soft_when_ordered_physical_otherwise() throws Exception {
        // Le routeur figure dans une commande : suppression logique, images supprimées de Cloudinary
        inventory.reserve(customerId, List.of(new ReserveItem(routerId, null, 1)));
        clearInvocations(storage);
        send(delete("/api/admin/products/" + routerId), admin, null).andExpect(status().isNoContent());
        verify(storage).delete(routerPublicId);
        assertThat(productRepository.findById(routerId)).isPresent();
        assertThat(productRepository.findById(routerId).orElseThrow().getDeletedAt()).isNotNull();
        send(get("/api/admin/products/" + routerId), admin, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/products").param("q", "routeur")).andExpect(jsonPath("$.totalElements").value(0));

        // Jamais commandé : suppression physique
        send(delete("/api/admin/products/" + jblId), admin, null).andExpect(status().isNoContent());
        assertThat(productRepository.findById(jblId)).isEmpty();
        send(get("/api/me/favorites"), customer, null).andExpect(jsonPath("$.length()").value(0));
    }
}
