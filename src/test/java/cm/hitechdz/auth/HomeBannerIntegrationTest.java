package cm.hitechdz.auth;

import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.dto.ErrorResponse;
import cm.hitechdz.auth.exception.GlobalExceptionHandler;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bannières d'accueil : normes d'image, liens, limite par emplacement, visibilité, sécurité. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:banners;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HomeBannerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ImageStorageService storage;

    static String admin, customer, productSlug;
    static long firstMain;
    static final AtomicInteger UPLOADS = new AtomicInteger();
    static final AtomicInteger IP = new AtomicInteger(1);

    @BeforeEach
    void fakeCloudinary() {
        when(storage.uploadBanner(any(), anyString())).thenAnswer(inv -> {
            String folder = inv.getArgument(1);
            int n = UPLOADS.incrementAndGet();
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/img" + n + ".png",
                    folder + "/img" + n, null, null);
        });
    }

    // ------------------------------------------------------------------ helpers
    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** PNG de bruit aléatoire : lourd (incompressible). */
    static byte[] noisyPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(42);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] gif(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED), "gif", out);
        return out.toByteArray();
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.6." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private ResultActions upload(HttpMethod method, String url, String token, byte[] image, byte[] mobile,
                                 Map<String, Object> data) throws Exception {
        MockMultipartHttpServletRequestBuilder b = multipart(method, url);
        if (image != null) b.file(new MockMultipartFile("image", "affiche.png", "image/png", image));
        if (mobile != null) b.file(new MockMultipartFile("mobileImage", "mobile.png", "image/png", mobile));
        if (data != null) b.file(new MockMultipartFile("data", "", "application/json", json.writeValueAsBytes(data)));
        return send(b, token, null);
    }

    private ResultActions create(byte[] image, Map<String, Object> data) throws Exception {
        return upload(HttpMethod.POST, "/api/admin/banners", admin, image, null, data);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static Map<String, Object> data(String slot, String alt) {
        Map<String, Object> m = new HashMap<>();
        m.put("slot", slot);
        m.put("altText", alt);
        m.put("linkType", "NONE");
        return m;
    }

    // ------------------------------------------------------------------ tests
    @Test @Order(1)
    void setup_public_empty_and_specs() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Ines", "lastName", "Test",
                "email", "ines@example.cm", "phone", "6 70 00 00 06", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Smartphones", "sub", List.of("Android")))
                .andExpect(status().isCreated());
        productSlug = body(send(post("/api/admin/products"), admin, Map.of("name", "Galaxy A55", "category", "smartphones",
                "price", 250_000, "stock", 3, "status", "published")).andExpect(status().isCreated())).get("slug").asText();
        send(post("/api/admin/products"), admin, Map.of("name", "Brouillon X", "category", "smartphones",
                "price", 1000, "stock", 1, "status", "draft")).andExpect(status().isCreated());

        mvc.perform(get("/api/banners/home"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.MAIN.length()").value(0))
                .andExpect(jsonPath("$.SIDE_TOP.length()").value(0))
                .andExpect(jsonPath("$.SIDE_BOTTOM.length()").value(0));

        send(get("/api/admin/banners/specs"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[3]").value("image/gif"))
                .andExpect(jsonPath("$.maxBytes").value(2_097_152))
                .andExpect(jsonPath("$.maxGifBytes").value(4_194_304))
                .andExpect(jsonPath("$.maxPerSlot").value(6))
                .andExpect(jsonPath("$.slots.MAIN.ratio").value("16:9"))
                .andExpect(jsonPath("$.slots.MAIN.minWidth").value(1200))
                .andExpect(jsonPath("$.slots.SIDE_TOP.recommended").value("800x600"))
                .andExpect(jsonPath("$.slots.SIDE_BOTTOM.minHeight").value(450));
    }

    @Test @Order(2)
    void admin_security() throws Exception {
        send(get("/api/admin/banners"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/banners"), customer, null).andExpect(status().isForbidden());
        send(get("/api/admin/banners/specs"), customer, null).andExpect(status().isForbidden());
        upload(HttpMethod.POST, "/api/admin/banners", customer, png(1600, 900), null, data("MAIN", "Promo"))
                .andExpect(status().isForbidden());
        upload(HttpMethod.POST, "/api/admin/banners", null, png(1600, 900), null, data("MAIN", "Promo"))
                .andExpect(status().isUnauthorized());
        send(put("/api/admin/banners/reorder"), customer, Map.of("slot", "MAIN", "ids", List.of())).andExpect(status().isForbidden());
        send(patch("/api/admin/banners/1/active"), customer, Map.of("active", false)).andExpect(status().isForbidden());
        send(delete("/api/admin/banners/1"), customer, null).andExpect(status().isForbidden());
        verify(storage, never()).uploadBanner(any(), anyString());
    }

    @Test @Order(3)
    void image_rules_format_weight_dimensions_ratio() throws Exception {
        // Format : vérifié sur les octets, pas sur l'extension
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1600\" height=\"900\"></svg>".getBytes(StandardCharsets.UTF_8);
        create(svg, data("MAIN", "Affiche SVG")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Format d'image refusé"))
                .andExpect(jsonPath("$.fieldErrors.image").value("Format SVG refusé : utilisez JPEG, PNG, WebP ou GIF."));
        create("pas une image du tout, juste du texte".getBytes(), data("MAIN", "Faux PNG"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("non reconnu")));
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB), "bmp", bmp);
        create(bmp.toByteArray(), data("MAIN", "Affiche BMP")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("BMP")));

        // Poids : 2 Mo maximum (hors GIF)
        byte[] heavy = noisyPng(1600, 900);
        assertThat(heavy.length).isBetween(2 * 1024 * 1024 + 1, 5 * 1024 * 1024);
        create(heavy, data("MAIN", "Affiche lourde")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image invalide"))
                .andExpect(jsonPath("$.fieldErrors.image", containsString("L'image dépasse 2 Mo")));

        // Dimensions minimales, maximales et ratio
        create(png(1000, 562), data("MAIN", "Trop petite")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("L'image doit faire au moins 1200×675 px. Reçue : 1000×562 px."));
        create(png(1300, 1300), data("MAIN", "Carrée")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image")
                        .value("L'image doit être au format 16:9 (ex. 1600×900 px). Reçue : 1300×1300 px."));
        create(png(4100, 2306), data("MAIN", "Trop grande")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("4000×4000")));
        create(png(500, 375), data("SIDE_TOP", "Petite latérale")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("600×450")));
        create(png(1600, 900), data("SIDE_BOTTOM", "Mauvais ratio")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("4:3")));
        // Version mobile : 750 px de large minimum
        upload(HttpMethod.POST, "/api/admin/banners", admin, png(1600, 900), png(700, 900), data("MAIN", "Mobile étroite"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.mobileImage", containsString("750 px")));

        verify(storage, never()).uploadBanner(any(), anyString());   // rien n'est envoyé si l'image est refusée
    }

    @Test @Order(4)
    void data_and_link_validation() throws Exception {
        create(png(1600, 900), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.data").exists());
        create(null, data("MAIN", "Sans image")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("L'image est obligatoire."));
        create(png(1600, 900), data("CENTRE", "Ab")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.slot").exists())
                .andExpect(jsonPath("$.fieldErrors.altText").exists());

        Map<String, Object> d = data("MAIN", "Nouveautés smartphones");
        d.put("linkType", "CATEGORY");
        d.put("linkValue", "inconnue");
        create(png(1600, 900), d).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Cette catégorie n'existe pas."));
        d.put("linkValue", null);
        create(png(1600, 900), d).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue", containsString("obligatoire")));
        d.put("linkType", "SUBCATEGORY");
        d.put("linkValue", "smartphones/iphone");
        create(png(1600, 900), d).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.linkValue").exists());
        d.put("linkType", "PRODUCT");
        d.put("linkValue", "brouillon-x");
        create(png(1600, 900), d).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Ce produit n'existe pas ou n'est pas publié."));
        d.put("linkType", "URL");
        d.put("linkValue", "http://exemple.cm");
        create(png(1600, 900), d).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.linkValue").exists());
        d.put("linkValue", "javascript:alert(1)");
        create(png(1600, 900), d).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.linkValue").exists());
        d.put("linkType", "LIEN");
        create(png(1600, 900), d).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.linkType").exists());

        Map<String, Object> dates = data("MAIN", "Dates inversées");
        dates.put("startsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        dates.put("endsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        create(png(1600, 900), dates).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endsAt").value("La date de fin doit être postérieure à la date de début."));
        verify(storage, never()).uploadBanner(any(), anyString());
    }

    @Test @Order(5)
    void create_with_valid_links_and_public_listing() throws Exception {
        Map<String, Object> d = data("MAIN", "Les smartphones Android du moment");
        d.put("linkType", "SUBCATEGORY");
        d.put("linkValue", "smartphones/android");
        JsonNode b = body(upload(HttpMethod.POST, "/api/admin/banners", admin, png(1600, 900), png(800, 1000), d)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slot").value("MAIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.width").value(1600))
                .andExpect(jsonPath("$.height").value(900))
                .andExpect(jsonPath("$.format").value("png"))
                .andExpect(jsonPath("$.sizeBytes").isNumber())
                .andExpect(jsonPath("$.mobileWidth").value(800))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.imageUrl", containsString("/image/upload/f_auto,q_auto/")))
                .andExpect(jsonPath("$.imagePublicId", startsWithText("hitech-dz/banners/main/"))));
        firstMain = b.get("id").asLong();

        Map<String, Object> p = data("SIDE_TOP", "Galaxy A55 en promotion");
        p.put("linkType", "PRODUCT");
        p.put("linkValue", productSlug);
        create(png(800, 600), p).andExpect(status().isCreated());
        Map<String, Object> u = data("SIDE_BOTTOM", "Offre partenaire");
        u.put("linkType", "URL");
        u.put("linkValue", "https://exemple.cm/offre");
        // GIF accepté, ratio 4:3 avec tolérance (820×600 ≈ 1,367 soit +2,5 %)
        upload(HttpMethod.POST, "/api/admin/banners", admin, gif(820, 600), null, u).andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("gif"));
        Map<String, Object> c = data("SIDE_BOTTOM", "Catégorie smartphones");
        c.put("linkType", "CATEGORY");
        c.put("linkValue", "smartphones");
        create(png(800, 600), c).andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(1));

        // Programmée, expirée, inactive : absentes du public
        Map<String, Object> future = data("MAIN", "Black Friday");
        future.put("startsAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        create(png(1600, 900), future).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        Map<String, Object> past = data("MAIN", "Rentrée scolaire");
        past.put("startsAt", Instant.now().minus(10, ChronoUnit.DAYS).toString());
        past.put("endsAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        create(png(1600, 900), past).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("EXPIRED"));
        Map<String, Object> off = data("MAIN", "Affiche désactivée");
        off.put("active", false);
        create(png(1600, 900), off).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("INACTIVE"));

        mvc.perform(get("/api/banners/home"))
                .andExpect(jsonPath("$.MAIN.length()").value(1))
                .andExpect(jsonPath("$.MAIN[0].id").value(firstMain))
                .andExpect(jsonPath("$.MAIN[0].altText").value("Les smartphones Android du moment"))
                .andExpect(jsonPath("$.MAIN[0].linkType").value("SUBCATEGORY"))
                .andExpect(jsonPath("$.MAIN[0].linkValue").value("smartphones/android"))
                .andExpect(jsonPath("$.MAIN[0].mobileImageUrl", containsString("f_auto,q_auto")))
                .andExpect(jsonPath("$.MAIN[0].status").doesNotExist())
                .andExpect(jsonPath("$.MAIN[0].imagePublicId").doesNotExist())
                .andExpect(jsonPath("$.SIDE_TOP[0].linkValue").value(productSlug))
                .andExpect(jsonPath("$.SIDE_BOTTOM.length()").value(2))
                .andExpect(jsonPath("$.SIDE_BOTTOM[0].linkType").value("URL"));

        send(get("/api/admin/banners").param("slot", "MAIN"), admin, null)
                .andExpect(jsonPath("$.length()").value(4));
        send(get("/api/admin/banners").param("slot", "MAIN").param("active", "false"), admin, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("INACTIVE"));
        send(get("/api/admin/banners").param("slot", "CENTRE"), admin, null).andExpect(status().isBadRequest());
        send(get("/api/admin/banners/" + firstMain), admin, null).andExpect(status().isOk());
        send(get("/api/admin/banners/99999"), admin, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Affiche introuvable."));
    }

    @Test @Order(6)
    void max_six_active_per_slot() throws Exception {
        // MAIN : 1 active + 1 programmée (comptent), 1 expirée et 1 inactive (ne comptent pas) → 4 de plus possibles
        for (int i = 0; i < 4; i++) {
            create(png(1600, 900), data("MAIN", "Affiche " + i)).andExpect(status().isCreated());
        }
        create(png(1600, 900), data("MAIN", "Septième affiche")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Nombre maximum d'affiches atteint pour cet emplacement"));
        Map<String, Object> off = data("MAIN", "Réserve inactive");
        off.put("active", false);
        long reserve = body(create(png(1600, 900), off).andExpect(status().isCreated())).get("id").asLong();
        send(patch("/api/admin/banners/" + reserve + "/active"), admin, Map.of("active", true)).andExpect(status().isConflict());
        send(patch("/api/admin/banners/" + firstMain + "/active"), admin, Map.of("active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        send(patch("/api/admin/banners/" + reserve + "/active"), admin, Map.of("active", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        send(patch("/api/admin/banners/" + reserve + "/active"), admin, Map.of()).andExpect(status().isBadRequest());
        send(patch("/api/admin/banners/99999/active"), admin, Map.of("active", true)).andExpect(status().isNotFound());
    }

    @Test @Order(7)
    void reorder_atomically_by_slot() throws Exception {
        JsonNode side = body(send(get("/api/admin/banners").param("slot", "SIDE_BOTTOM"), admin, null));
        long a = side.get(0).get("id").asLong(), b = side.get(1).get("id").asLong();
        send(put("/api/admin/banners/reorder"), admin, Map.of("slot", "SIDE_BOTTOM", "ids", List.of(b, a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(b))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[1].position").value(1));
        mvc.perform(get("/api/banners/home")).andExpect(jsonPath("$.SIDE_BOTTOM[0].id").value(b));
        send(put("/api/admin/banners/reorder"), admin, Map.of("slot", "SIDE_BOTTOM", "ids", List.of(b)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.ids").exists());
        send(put("/api/admin/banners/reorder"), admin, Map.of("slot", "SIDE_BOTTOM", "ids", List.of(a, b, firstMain)))
                .andExpect(status().isBadRequest());
        send(put("/api/admin/banners/reorder"), admin, Map.of("slot", "CENTRE", "ids", List.of()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/banners/home")).andExpect(jsonPath("$.SIDE_BOTTOM[0].id").value(b));
    }

    @Test @Order(8)
    void update_replaces_image_and_deletes_old_one() throws Exception {
        JsonNode before = body(send(get("/api/admin/banners/" + firstMain), admin, null));
        String oldImage = before.get("imagePublicId").asText();
        String oldMobile = before.get("mobileImagePublicId").asText();

        Map<String, Object> d = data("MAIN", "Android : nouvelle affiche");
        d.put("linkType", "CATEGORY");
        d.put("linkValue", "smartphones");
        d.put("removeMobileImage", true);
        upload(HttpMethod.PUT, "/api/admin/banners/" + firstMain, admin, png(1920, 1080), null, d)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.altText").value("Android : nouvelle affiche"))
                .andExpect(jsonPath("$.width").value(1920))
                .andExpect(jsonPath("$.mobileImageUrl").isEmpty())
                .andExpect(jsonPath("$.active").value(false));   // absent de data : inchangé
        verify(storage).delete(oldImage);
        verify(storage).delete(oldMobile);

        // Sans nouvelle image : données seules ; changer d'emplacement exige une image au bon ratio
        upload(HttpMethod.PUT, "/api/admin/banners/" + firstMain, admin, null, null, data("MAIN", "Texte modifié"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.width").value(1920));
        upload(HttpMethod.PUT, "/api/admin/banners/" + firstMain, admin, null, null, data("SIDE_TOP", "Déplacée"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("Envoyez une nouvelle image")));
        upload(HttpMethod.PUT, "/api/admin/banners/" + firstMain, admin, png(800, 600), null, data("SIDE_TOP", "Déplacée"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value("SIDE_TOP"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.imagePublicId", startsWithText("hitech-dz/banners/side-top/")));
        upload(HttpMethod.PUT, "/api/admin/banners/" + firstMain, admin, png(800, 600), null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.data").exists());
        upload(HttpMethod.PUT, "/api/admin/banners/99999", admin, null, null, data("MAIN", "Inconnue"))
                .andExpect(status().isNotFound());
    }

    @Test @Order(9)
    void failed_upload_rolls_back_and_cleans_cloudinary() throws Exception {
        when(storage.uploadBanner(any(), anyString()))
                .thenReturn(new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/x.png", "hitech-dz/banners/side-top/orphan", null, null))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "L'envoi de l'image a échoué. Réessayez."));
        int countBefore = body(send(get("/api/admin/banners"), admin, null)).size();
        upload(HttpMethod.POST, "/api/admin/banners", admin, png(800, 600), png(800, 1000), data("SIDE_TOP", "Échec mobile"))
                .andExpect(status().isBadGateway());
        verify(storage).delete("hitech-dz/banners/side-top/orphan");
        assertThat(body(send(get("/api/admin/banners"), admin, null)).size()).isEqualTo(countBefore);
    }

    @Test @Order(10)
    void delete_removes_cloudinary_images() throws Exception {
        JsonNode b = body(send(get("/api/admin/banners/" + firstMain), admin, null));
        send(delete("/api/admin/banners/" + firstMain), admin, null).andExpect(status().isNoContent());
        verify(storage).delete(b.get("imagePublicId").asText());
        send(delete("/api/admin/banners/" + firstMain), admin, null).andExpect(status().isNotFound());
        send(get("/api/admin/banners/" + firstMain), admin, null).andExpect(status().isNotFound());
    }

    @Test @Order(11)
    void too_large_upload_gives_413_only_for_banners() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest banner = new MockHttpServletRequest("POST", "/api/admin/banners");
        ResponseEntity<ErrorResponse> r = handler.handleMaxUpload(new MaxUploadSizeExceededException(5 * 1024 * 1024), banner);
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody().fieldErrors()).containsKey("image");
        MockHttpServletRequest product = new MockHttpServletRequest("POST", "/api/admin/products/p1/images");
        assertThat(handler.handleMaxUpload(new MaxUploadSizeExceededException(5 * 1024 * 1024), product)
                .getStatusCode().value()).isEqualTo(400);   // comportement existant inchangé
    }

    private static org.hamcrest.Matcher<String> startsWithText(String prefix) {
        return org.hamcrest.Matchers.startsWith(prefix);
    }
}
