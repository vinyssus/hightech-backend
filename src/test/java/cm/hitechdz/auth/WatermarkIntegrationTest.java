package cm.hitechdz.auth;

import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.StoredImage;
import cm.hitechdz.auth.service.media.Watermark;
import cm.hitechdz.auth.service.media.WatermarkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logo HITECH DZ incrusté automatiquement sur les nouvelles photos produits. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:watermark;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WatermarkIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ImageStorageService storage;

    static String admin, customer, productId;
    static final AtomicInteger N = new AtomicInteger();
    static final AtomicInteger IP = new AtomicInteger(1);

    @BeforeEach
    void fakeCloudinary() {
        when(storage.uploadBrandAsset(any(), anyString())).thenAnswer(inv -> new StoredImage(
                "https://res.cloudinary.com/demo/image/upload/v1/" + inv.getArgument(1) + ".png", inv.getArgument(1), 821, 340));
        when(storage.upload(any(), anyString(), any())).thenAnswer(inv -> {
            String folder = inv.getArgument(1);
            int n = N.incrementAndGet();
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/p" + n + ".png",
                    folder + "/p" + n, 1200, 1200);
        });
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.11." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static byte[] image(String format, int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private ResultActions uploadProductImage(String url, String token, Boolean watermark) throws Exception {
        var b = multipart(url).file(new MockMultipartFile("files", "photo.jpg", "image/jpeg", image("jpg", 1200, 1200)));
        if (watermark != null) b.param("watermark", watermark.toString());
        return send(b, token, null);
    }

    private Watermark lastWatermark() {
        ArgumentCaptor<Watermark> captor = ArgumentCaptor.forClass(Watermark.class);
        verify(storage, org.mockito.Mockito.atLeastOnce()).upload(any(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test @Order(1)
    void defaults_and_security() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customer = body(send(post("/api/auth/register"), null, Map.of("firstName", "Luc", "lastName", "Test",
                "email", "luc@example.cm", "phone", "6 70 00 00 09", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Sécurité")).andExpect(status().isCreated());
        productId = body(send(post("/api/admin/products"), admin, Map.of("name", "Caméra IP", "category", "securite",
                "price", 45_000, "stock", 3, "status", "published")).andExpect(status().isCreated())).get("id").asText();

        send(get("/api/admin/watermark"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.position").value("SOUTH_EAST"))
                .andExpect(jsonPath("$.widthPercent").value(33))
                .andExpect(jsonPath("$.marginPercent").value(3))
                .andExpect(jsonPath("$.opacity").value(100))
                .andExpect(jsonPath("$.customLogo").value(false))
                .andExpect(jsonPath("$.logoUrl").isEmpty())
                .andExpect(jsonPath("$.positions.length()").value(6));
        send(get("/api/admin/watermark"), customer, null).andExpect(status().isForbidden());
        send(get("/api/admin/watermark"), null, null).andExpect(status().isUnauthorized());
        send(put("/api/admin/watermark"), customer, Map.of("enabled", false, "position", "SOUTH", "widthPercent", 30,
                "marginPercent", 3, "opacity", 100)).andExpect(status().isForbidden());
        assertThat(WatermarkServiceDefaultLogo.bytes()).hasSizeGreaterThan(1000);
    }

    @Test @Order(2)
    void product_photos_get_the_logo_and_default_logo_is_sent_once() throws Exception {
        uploadProductImage("/api/admin/uploads/images", admin, null).andExpect(status().isCreated());
        verify(storage, times(1)).uploadBrandAsset(any(), eq(WatermarkService.DEFAULT_LOGO_PUBLIC_ID));
        assertThat(lastWatermark()).isEqualTo(new Watermark(WatermarkService.DEFAULT_LOGO_PUBLIC_ID, "south_east", 0.33, 0.03, 100));

        uploadProductImage("/api/admin/products/" + productId + "/images", admin, null).andExpect(status().isOk());
        send(multipart("/api/admin/uploads").file(new MockMultipartFile("files", "a.jpg", "image/jpeg", image("jpg", 800, 800))),
                admin, null).andExpect(status().isOk());
        verify(storage, times(1)).uploadBrandAsset(any(), anyString());   // pas renvoyé à chaque photo
        send(get("/api/admin/watermark"), admin, null)
                .andExpect(jsonPath("$.logoUrl").value(org.hamcrest.Matchers.containsString("watermark-default")));
    }

    @Test @Order(3)
    void opt_out_per_upload_and_brand_logos_never_get_it() throws Exception {
        uploadProductImage("/api/admin/uploads/images", admin, false).andExpect(status().isCreated());
        verify(storage).upload(any(), eq("hitech-dz/tmp"), isNull());
        send(multipart("/api/admin/brands").file(new MockMultipartFile("logo", "hik.png", "image/png", image("png", 400, 400)))
                .param("name", "Hikvision"), admin, null).andExpect(status().isCreated());
        verify(storage).upload(any(), eq("hitech-dz/brands"), isNull());
    }

    @Test @Order(4)
    void settings_validation_and_disable() throws Exception {
        send(put("/api/admin/watermark"), admin, Map.of("enabled", true, "position", "MILIEU", "widthPercent", 70,
                "marginPercent", 12, "opacity", 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.widthPercent").exists())
                .andExpect(jsonPath("$.fieldErrors.marginPercent").exists())
                .andExpect(jsonPath("$.fieldErrors.opacity").exists());
        send(put("/api/admin/watermark"), admin, Map.of("enabled", true, "position", "MILIEU", "widthPercent", 30,
                "marginPercent", 3, "opacity", 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.position").exists());
        send(put("/api/admin/watermark"), admin, Map.of("enabled", true, "position", "south", "widthPercent", 25,
                "marginPercent", 5, "opacity", 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("SOUTH"));
        uploadProductImage("/api/admin/uploads/images", admin, null).andExpect(status().isCreated());
        assertThat(lastWatermark()).isEqualTo(new Watermark(WatermarkService.DEFAULT_LOGO_PUBLIC_ID, "south", 0.25, 0.05, 80));

        send(put("/api/admin/watermark"), admin, Map.of("enabled", false, "position", "SOUTH_EAST", "widthPercent", 33,
                "marginPercent", 3, "opacity", 100)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        uploadProductImage("/api/admin/uploads/images", admin, null).andExpect(status().isCreated());
        verify(storage).upload(any(), eq("hitech-dz/tmp"), isNull());
        send(put("/api/admin/watermark"), admin, Map.of("enabled", true, "position", "SOUTH_EAST", "widthPercent", 33,
                "marginPercent", 3, "opacity", 100)).andExpect(status().isOk());
    }

    @Test @Order(5)
    void replace_and_reset_logo() throws Exception {
        send(multipart("/api/admin/watermark/logo").file(new MockMultipartFile("file", "logo.jpg", "image/jpeg", image("jpg", 800, 300))),
                admin, null).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.fieldErrors.file").value("Le logo doit être un PNG (ou WebP) à fond transparent."));
        send(multipart("/api/admin/watermark/logo").file(new MockMultipartFile("file", "petit.png", "image/png", image("png", 200, 80))),
                admin, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.file").exists());
        send(multipart("/api/admin/watermark/logo"), admin, null).andExpect(status().isBadRequest());

        JsonNode s = body(send(multipart("/api/admin/watermark/logo").file(new MockMultipartFile("file", "logo.png", "image/png", image("png", 900, 360))),
                admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customLogo").value(true)));
        String custom = s.get("logoUrl").asText();
        assertThat(custom).contains("hitech-dz/brand/watermark-");
        uploadProductImage("/api/admin/uploads/images", admin, null).andExpect(status().isCreated());
        assertThat(lastWatermark().logoPublicId()).startsWith("hitech-dz/brand/watermark-").isNotEqualTo(WatermarkService.DEFAULT_LOGO_PUBLIC_ID);
        String customId = lastWatermark().logoPublicId();

        send(delete("/api/admin/watermark/logo"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customLogo").value(false));
        verify(storage).delete(customId);
        uploadProductImage("/api/admin/uploads/images", admin, null).andExpect(status().isCreated());
        assertThat(lastWatermark().logoPublicId()).isEqualTo(WatermarkService.DEFAULT_LOGO_PUBLIC_ID);
        send(delete("/api/admin/watermark/logo"), customer, null).andExpect(status().isForbidden());
    }

    /** Accès au logo intégré (ressource de l'application). */
    static final class WatermarkServiceDefaultLogo {
        static byte[] bytes() throws Exception {
            try (var in = new org.springframework.core.io.ClassPathResource(WatermarkService.DEFAULT_LOGO_RESOURCE).getInputStream()) {
                return in.readAllBytes();
            }
        }
    }
}
