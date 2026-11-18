package cm.hitechdz.auth;

import cm.hitechdz.auth.dto.ErrorResponse;
import cm.hitechdz.auth.exception.GlobalExceptionHandler;
import cm.hitechdz.auth.service.media.ImageInspector;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

/** Panneau publicitaire : normes d'image (dont animation GIF), liens, limite de 6, dates (Douala), réglages, sécurité. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:promopopup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromoPopupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ImageStorageService storage;

    static String admin, customer, productSlug, firstId;
    static final AtomicInteger UPLOADS = new AtomicInteger();
    static final AtomicInteger IP = new AtomicInteger(1);

    @BeforeEach
    void fakeCloudinary() {
        when(storage.uploadBanner(any(), anyString())).thenAnswer(inv -> {
            String folder = inv.getArgument(1);
            int n = UPLOADS.incrementAndGet();
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/ad" + n + ".gif",
                    folder + "/ad" + n, null, null);
        });
    }

    // ------------------------------------------------------------------ images
    /** GIF animé construit octet par octet : frames images, délai en centièmes de seconde. */
    static byte[] gif(int w, int h, int frames, int delayCs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes());
        out.write(w & 0xFF); out.write(w >> 8); out.write(h & 0xFF); out.write(h >> 8);
        out.write(0x80); out.write(0); out.write(0);            // palette globale de 2 couleurs
        out.writeBytes(new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        out.writeBytes(new byte[]{0x21, (byte) 0xFF, 0x0B});    // boucle NETSCAPE2.0
        out.writeBytes("NETSCAPE2.0".getBytes());
        out.writeBytes(new byte[]{0x03, 0x01, 0x00, 0x00, 0x00});
        for (int i = 0; i < frames; i++) {
            out.writeBytes(new byte[]{0x21, (byte) 0xF9, 0x04, 0x00, (byte) (delayCs & 0xFF), (byte) (delayCs >> 8), 0x00, 0x00});
            out.write(0x2C);
            out.writeBytes(new byte[]{0, 0, 0, 0, (byte) (w & 0xFF), (byte) (w >> 8), (byte) (h & 0xFF), (byte) (h >> 8), 0});
            out.writeBytes(new byte[]{0x02, 0x02, 0x44, 0x01, 0x00});
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static byte[] noisyPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ helpers
    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.7." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private ResultActions upload(HttpMethod method, String url, String token, byte[] image, byte[] mobile,
                                 Map<String, Object> data) throws Exception {
        MockMultipartHttpServletRequestBuilder b = multipart(method, url);
        if (image != null) b.file(new MockMultipartFile("image", "pub.gif", "image/gif", image));
        if (mobile != null) b.file(new MockMultipartFile("mobileImage", "pub-mobile.gif", "image/gif", mobile));
        if (data != null) b.file(new MockMultipartFile("data", "", "application/json", json.writeValueAsBytes(data)));
        return send(b, token, null);
    }

    private ResultActions create(byte[] image, Map<String, Object> data) throws Exception {
        return upload(HttpMethod.POST, "/api/admin/promo-popup/ads", admin, image, null, data);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static Map<String, Object> data(String alt) {
        Map<String, Object> m = new HashMap<>();
        m.put("altText", alt);
        m.put("linkType", "NONE");
        return m;
    }

    static Map<String, Object> data(String alt, String linkType, String linkValue) {
        Map<String, Object> m = data(alt);
        m.put("linkType", linkType);
        m.put("linkValue", linkValue);
        return m;
    }

    // ------------------------------------------------------------------ tests
    @Test @Order(1)
    void defaults_specs_and_unit_gif_parsing() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Yann", "lastName", "Test",
                "email", "yann@example.cm", "phone", "6 70 00 00 07", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Audio", "sub", List.of("Écouteurs"))).andExpect(status().isCreated());
        productSlug = body(send(post("/api/admin/products"), admin, Map.of("name", "Buds Pro", "category", "audio",
                "price", 30_000, "stock", 5, "status", "published")).andExpect(status().isCreated())).get("slug").asText();

        mvc.perform(get("/api/promo-popup"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.settings.enabled").value(true))
                .andExpect(jsonPath("$.settings.delayMs").value(1500))
                .andExpect(jsonPath("$.settings.intervalMs").value(4000))
                .andExpect(jsonPath("$.settings.showOnMobile").value(true))
                .andExpect(jsonPath("$.settings.dismissMode").value("SESSION"))
                .andExpect(jsonPath("$.ads.length()").value(0))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(get("/api/promo-popup/specs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[0]").value("image/gif"))
                .andExpect(jsonPath("$.maxGifBytes").value(3 * 1024 * 1024))
                .andExpect(jsonPath("$.maxBytes").value(1024 * 1024))
                .andExpect(jsonPath("$.ratio").value("9:16"))
                .andExpect(jsonPath("$.minWidth").value(360))
                .andExpect(jsonPath("$.maxHeight").value(1920))
                .andExpect(jsonPath("$.maxGifFrames").value(150))
                .andExpect(jsonPath("$.maxGifDurationMs").value(15000))
                .andExpect(jsonPath("$.maxActive").value(6))
                .andExpect(jsonPath("$.delayMs.min").value(500))
                .andExpect(jsonPath("$.intervalMs.max").value(15000))
                .andExpect(jsonPath("$.catalogueValues[1]").value("nouveautes"))
                .andExpect(jsonPath("$.timezone").value("Africa/Douala"));

        assertThat(ImageInspector.gifAnimation(gif(720, 1280, 40, 8)).orElseThrow())
                .isEqualTo(new ImageInspector.GifAnimation(40, 3200));
        assertThat(ImageInspector.gifAnimation(gif(720, 1280, 10, 0)).orElseThrow().durationMs()).isEqualTo(1000);
        byte[] truncated = gif(720, 1280, 3, 10);
        assertThat(ImageInspector.gifAnimation(java.util.Arrays.copyOf(truncated, truncated.length - 4))).isEmpty();
    }

    @Test @Order(2)
    void security() throws Exception {
        send(get("/api/admin/promo-popup/ads"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/promo-popup/ads"), customer, null).andExpect(status().isForbidden());
        send(get("/api/admin/promo-popup/settings"), customer, null).andExpect(status().isForbidden());
        send(put("/api/admin/promo-popup/settings"), customer, Map.of("enabled", false, "delayMs", 1500,
                "intervalMs", 4000, "showOnMobile", true, "dismissMode", "SESSION")).andExpect(status().isForbidden());
        upload(HttpMethod.POST, "/api/admin/promo-popup/ads", customer, gif(720, 1280, 10, 10), null, data("Pub pirate"))
                .andExpect(status().isForbidden());
        send(put("/api/admin/promo-popup/ads/reorder"), customer, Map.of("ids", List.of())).andExpect(status().isForbidden());
        send(delete("/api/admin/promo-popup/ads/" + java.util.UUID.randomUUID()), customer, null).andExpect(status().isForbidden());
        // Seuls les GET publics sont ouverts
        send(post("/api/promo-popup"), null, Map.of()).andExpect(status().isUnauthorized());
        verify(storage, never()).uploadBanner(any(), anyString());
    }

    @Test @Order(3)
    void image_rules() throws Exception {
        create("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(), data("Pub SVG"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.fieldErrors.image").value("Format SVG refusé : utilisez GIF, WebP, PNG ou JPEG."));
        create("texte avec extension .gif".getBytes(), data("Faux GIF")).andExpect(status().isUnsupportedMediaType());

        byte[] bigGif = gif(720, 1280, 120, 10);
        byte[] padded = java.util.Arrays.copyOf(bigGif, 3 * 1024 * 1024 + 10);   // octets après la fin du GIF
        padded[padded.length - 1] = 0;
        create(padded, data("GIF trop lourd")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation échouée"))
                .andExpect(jsonPath("$.fieldErrors.image", containsString("Le GIF dépasse 3 Mo")));
        byte[] heavyPng = noisyPng(720, 1280);
        assertThat(heavyPng.length).isGreaterThan(1024 * 1024);
        create(heavyPng, data("PNG trop lourd")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("L'image dépasse 1 Mo")));

        create(gif(1280, 720, 10, 10), data("Horizontale")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image")
                        .value("L'image doit être au format vertical 9:16 (ex. 720×1280). Reçue : 1280×720 px."));
        create(gif(270, 480, 10, 10), data("Trop petite")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("L'image doit faire au moins 360×640 px. Reçue : 270×480 px."));
        create(gif(1440, 2560, 10, 10), data("Trop grande")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image", containsString("1080×1920")));
        create(gif(720, 1280, 180, 5), data("Trop d'images")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("Le GIF contient trop d'images (180, maximum 150)."));
        create(gif(720, 1280, 90, 20), data("Trop long")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("Le GIF dure trop longtemps (18,0 s, maximum 15 s)."));
        upload(HttpMethod.POST, "/api/admin/promo-popup/ads", admin, gif(720, 1280, 10, 10), gif(1280, 720, 5, 10), data("Mobile horizontale"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.mobileImage", containsString("9:16")));
        verify(storage, never()).uploadBanner(any(), anyString());
    }

    @Test @Order(4)
    void data_and_links_validation() throws Exception {
        create(gif(720, 1280, 10, 10), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.data").exists());
        create(null, data("Sans image")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.image").value("L'image est obligatoire."));
        create(gif(720, 1280, 10, 10), data("Ab")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.altText").exists());
        create(gif(720, 1280, 10, 10), data("x".repeat(121))).andExpect(status().isBadRequest());
        create(gif(720, 1280, 10, 10), data("Catégorie", "CATEGORY", "inconnue")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Catégorie introuvable."));
        create(gif(720, 1280, 10, 10), data("Sous-catégorie", "SUBCATEGORY", "audio/casques")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Sous-catégorie introuvable."));
        create(gif(720, 1280, 10, 10), data("Produit", "PRODUCT", "inexistant")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Produit introuvable ou non publié."));
        create(gif(720, 1280, 10, 10), data("Catalogue", "CATALOGUE", "soldes")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue", containsString("promo, nouveautes ou popularite")));
        create(gif(720, 1280, 10, 10), data("Externe", "EXTERNAL", "http://exemple.cm")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue", containsString("https://")));
        create(gif(720, 1280, 10, 10), data("Aucun lien", "NONE", "promo")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkValue").value("Aucun lien attendu quand le type est NONE."));
        create(gif(720, 1280, 10, 10), data("Type faux", "PAGE", "promo")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkType").exists());
        Map<String, Object> d = data("Dates");
        d.put("startsAt", "2026-12-10T10:00");
        d.put("endsAt", "2026-12-10T09:00");
        create(gif(720, 1280, 10, 10), d).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endsAt").value("La date de fin doit être postérieure à la date de début."));
        d.put("startsAt", "10/12/2026");
        create(gif(720, 1280, 10, 10), d).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.startsAt", containsString("heure de Douala")));
        verify(storage, never()).uploadBanner(any(), anyString());
    }

    @Test @Order(5)
    void create_and_public_filtering_with_douala_time() throws Exception {
        JsonNode a = body(create(gif(720, 1280, 40, 8), data("Smartphones en promotion", "CATALOGUE", "promo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.format").value("gif"))
                .andExpect(jsonPath("$.frames").value(40))
                .andExpect(jsonPath("$.durationMs").value(3200))
                .andExpect(jsonPath("$.width").value(720))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.createdBy").isNotEmpty())
                .andExpect(jsonPath("$.imagePublicId", containsString("hitech-dz/promo-popup/")))
                .andExpect(jsonPath("$.imageUrl", containsString("f_auto,q_auto"))));
        firstId = a.get("id").asText();
        create(gif(720, 1280, 10, 10), data("Écouteurs", "SUBCATEGORY", "audio/ecouteurs")).andExpect(status().isCreated());
        create(png(720, 1280), data("Buds Pro", "PRODUCT", productSlug)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.frames").isEmpty());
        create(gif(720, 1280, 10, 10), data("Partenaire", "EXTERNAL", "https://exemple.cm/offre")).andExpect(status().isCreated());

        // Dates sans fuseau = heure de Douala (UTC+1), stockées en UTC
        ZoneId douala = ZoneId.of("Africa/Douala");
        LocalDateTime tomorrow = LocalDateTime.now(douala).plusDays(1).withSecond(0).withNano(0);
        Map<String, Object> scheduled = data("Bientôt");
        scheduled.put("startsAt", tomorrow.toString());
        create(gif(720, 1280, 10, 10), scheduled).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startsAt").value(tomorrow.atZone(douala).toInstant().toString()))
                .andExpect(jsonPath("$.startsAtLocal").value(tomorrow.toString().substring(0, 16)));
        Map<String, Object> fixed = data("Date fixe");
        fixed.put("startsAt", "2026-01-01T00:00");
        fixed.put("endsAt", "2026-01-02T00:00");
        create(gif(720, 1280, 10, 10), fixed).andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsAt").value("2025-12-31T23:00:00Z"))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        Map<String, Object> started = data("Commencée à l'instant");
        started.put("startsAt", Instant.now().minus(5, ChronoUnit.MINUTES).toString());
        started.put("endsAt", LocalDateTime.now(douala).plusHours(2).toString());
        create(gif(720, 1280, 10, 10), started).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("LIVE"));

        mvc.perform(get("/api/promo-popup"))
                .andExpect(jsonPath("$.ads.length()").value(5))
                .andExpect(jsonPath("$.ads[0].id").value(firstId))
                .andExpect(jsonPath("$.ads[0].altText").value("Smartphones en promotion"))
                .andExpect(jsonPath("$.ads[0].linkType").value("CATALOGUE"))
                .andExpect(jsonPath("$.ads[0].linkValue").value("promo"))
                .andExpect(jsonPath("$.ads[0].status").doesNotExist())
                .andExpect(jsonPath("$.ads[0].imagePublicId").doesNotExist())
                .andExpect(jsonPath("$.ads[?(@.altText=='Bientôt')]").isEmpty())
                .andExpect(jsonPath("$.ads[?(@.altText=='Date fixe')]").isEmpty());
        send(get("/api/admin/promo-popup/ads"), admin, null).andExpect(jsonPath("$.length()").value(7));
        send(get("/api/admin/promo-popup/ads/" + firstId), admin, null).andExpect(status().isOk());
        send(get("/api/admin/promo-popup/ads/" + java.util.UUID.randomUUID()), admin, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Publicité introuvable."));
    }

    @Test @Order(6)
    void etag_and_settings() throws Exception {
        String etag = mvc.perform(get("/api/promo-popup")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/api/promo-popup").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));

        send(put("/api/admin/promo-popup/settings"), admin, Map.of("enabled", true, "delayMs", 400,
                "intervalMs", 20000, "showOnMobile", true, "dismissMode", "SESSION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.delayMs").exists())
                .andExpect(jsonPath("$.fieldErrors.intervalMs").exists());
        send(put("/api/admin/promo-popup/settings"), admin, Map.of("enabled", true, "delayMs", 1500,
                "intervalMs", 4000, "showOnMobile", true, "dismissMode", "TOUJOURS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.dismissMode").exists());
        send(put("/api/admin/promo-popup/settings"), admin, Map.of("enabled", false, "delayMs", 2000,
                "intervalMs", 5000, "showOnMobile", false, "dismissMode", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissMode").value("DAY"));
        send(get("/api/admin/promo-popup/settings"), admin, null).andExpect(jsonPath("$.enabled").value(false));

        // Panneau désactivé : plus aucune publicité publique ; l'ETag change
        mvc.perform(get("/api/promo-popup").header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(false))
                .andExpect(jsonPath("$.settings.delayMs").value(2000))
                .andExpect(jsonPath("$.settings.showOnMobile").value(false))
                .andExpect(jsonPath("$.ads.length()").value(0));
        send(put("/api/admin/promo-popup/settings"), admin, Map.of("enabled", true, "delayMs", 1500,
                "intervalMs", 4000, "showOnMobile", true, "dismissMode", "SESSION")).andExpect(status().isOk());
    }

    @Test @Order(7)
    void max_six_active() throws Exception {
        // 5 en ligne + 1 programmée = 6 qui comptent ; l'expirée ne compte pas
        create(gif(720, 1280, 10, 10), data("Septième")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Nombre maximum de publicités actives atteint (6)."));
        Map<String, Object> off = data("Réserve");
        off.put("active", false);
        String reserve = body(create(gif(720, 1280, 10, 10), off).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISABLED"))).get("id").asText();
        send(patch("/api/admin/promo-popup/ads/" + reserve + "/active"), admin, Map.of("active", true))
                .andExpect(status().isConflict());
        send(patch("/api/admin/promo-popup/ads/" + firstId + "/active"), admin, Map.of("active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        send(patch("/api/admin/promo-popup/ads/" + reserve + "/active"), admin, Map.of("active", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));
        send(patch("/api/admin/promo-popup/ads/" + reserve + "/active"), admin, Map.of()).andExpect(status().isBadRequest());
    }

    @Test @Order(8)
    void reorder_all_ids() throws Exception {
        JsonNode list = body(send(get("/api/admin/promo-popup/ads"), admin, null));
        List<String> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("id").asText()));
        java.util.Collections.reverse(ids);
        send(put("/api/admin/promo-popup/ads/reorder"), admin, Map.of("ids", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$[0].position").value(0));
        send(get("/api/admin/promo-popup/ads"), admin, null).andExpect(jsonPath("$[0].id").value(ids.get(0)));
        send(put("/api/admin/promo-popup/ads/reorder"), admin, Map.of("ids", ids.subList(0, 3)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.ids").exists());
        List<String> withDuplicate = new ArrayList<>(ids);
        withDuplicate.set(1, ids.get(0));
        send(put("/api/admin/promo-popup/ads/reorder"), admin, Map.of("ids", withDuplicate)).andExpect(status().isBadRequest());
    }

    @Test @Order(9)
    void update_keeps_or_replaces_images_and_delete_cleans_cloudinary() throws Exception {
        JsonNode before = body(send(get("/api/admin/promo-popup/ads/" + firstId), admin, null));
        // Sans image : l'image est gardée
        upload(HttpMethod.PUT, "/api/admin/promo-popup/ads/" + firstId, admin, null, null,
                data("Smartphones en promotion (modifiée)", "CATALOGUE", "nouveautes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagePublicId").value(before.get("imagePublicId").asText()))
                .andExpect(jsonPath("$.linkValue").value("nouveautes"))
                .andExpect(jsonPath("$.active").value(false));
        verify(storage, never()).delete(anyString());
        // Avec image : remplacée, l'ancienne supprimée de Cloudinary
        upload(HttpMethod.PUT, "/api/admin/promo-popup/ads/" + firstId, admin, gif(540, 960, 20, 10), gif(720, 1280, 20, 10),
                data("Nouvelle animation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.width").value(540))
                .andExpect(jsonPath("$.mobileImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.linkType").value("NONE"));
        verify(storage).delete(before.get("imagePublicId").asText());
        upload(HttpMethod.PUT, "/api/admin/promo-popup/ads/" + firstId, admin, gif(1280, 720, 5, 10), null, data("Mauvaise"))
                .andExpect(status().isBadRequest());
        upload(HttpMethod.PUT, "/api/admin/promo-popup/ads/" + java.util.UUID.randomUUID(), admin, null, null, data("Inconnue"))
                .andExpect(status().isNotFound());

        JsonNode current = body(send(get("/api/admin/promo-popup/ads/" + firstId), admin, null));
        send(delete("/api/admin/promo-popup/ads/" + firstId), admin, null).andExpect(status().isNoContent());
        verify(storage).delete(current.get("imagePublicId").asText());
        verify(storage).delete(current.get("mobileImagePublicId").asText());
        send(delete("/api/admin/promo-popup/ads/" + firstId), admin, null).andExpect(status().isNotFound());
    }

    @Test @Order(10)
    void too_large_upload_gives_413() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ErrorResponse> r = handler.handleMaxUpload(new MaxUploadSizeExceededException(5 * 1024 * 1024),
                new MockHttpServletRequest("POST", "/api/admin/promo-popup/ads"));
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody().fieldErrors().get("image")).contains("3 Mo pour un GIF");
    }
}
