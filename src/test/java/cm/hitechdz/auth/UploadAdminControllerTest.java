package cm.hitechdz.auth;

import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.StoredImage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/admin/uploads : contrat du formulaire produit (champ « files » → { urls }). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploads;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000",
        "app.cors.allowed-origins=https://hitech-momo-market.lovable.app,https://*.lovable.app"
})
@AutoConfigureMockMvc
class UploadAdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ImageStorageService storage;

    private final AtomicInteger counter = new AtomicInteger();

    @BeforeEach
    void mockStorage() {
        when(storage.upload(any(), anyString())).thenAnswer(inv -> {
            String original = ((org.springframework.web.multipart.MultipartFile) inv.getArgument(0)).getOriginalFilename();
            String name = original.substring(0, original.lastIndexOf('.'));
            String folder = inv.getArgument(1);
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/" + name + ".png",
                    folder + "/" + name, 800, 600);
        });
        when(storage.move(anyString(), anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            String target = inv.getArgument(1) + "/" + pid.substring(pid.lastIndexOf('/') + 1);
            return new StoredImage("https://res.cloudinary.com/demo/image/upload/v2/" + target + ".png", target, 800, 600);
        });
        clearInvocations(storage);
    }

    private String token(String email, boolean register) throws Exception {
        if (register) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    Map.of("firstName", "Client", "lastName", "Test", "email", email,
                            "phone", "6 77 00 00 09", "password", "Secret123"))));
        }
        String password = register ? "Secret123" : "Admin123";
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private static byte[] png(int size) {
        byte[] b = new byte[Math.max(size, 8)];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    }

    private static MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("files", name, type, content);
    }

    private MockMultipartHttpServletRequestBuilder upload(String token, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/admin/uploads");
        for (MockMultipartFile f : files) b.file(f);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    @Test
    void uploads_return_urls_in_file_order_and_are_recognised_when_saving_a_product() throws Exception {
        String admin = token("admin@hitech-dz.cm", false);

        mvc.perform(upload(admin, file("one.png", "image/png", png(100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(1))
                .andExpect(jsonPath("$.urls[0]").value(
                        "https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v1/hitech-dz/products/one.png"));
        verify(storage).upload(any(), eq("hitech-dz/products"));

        String body = mvc.perform(upload(admin,
                        file("a.png", "image/png", png(100)),
                        file("b.jpg", "image/jpeg", jpeg()),
                        file("c.png", "image/png", png(100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(3))
                .andExpect(jsonPath("$.urls[0]").value(org.hamcrest.Matchers.endsWith("/a.png")))
                .andExpect(jsonPath("$.urls[1]").value(org.hamcrest.Matchers.endsWith("/b.png")))
                .andExpect(jsonPath("$.urls[2]").value(org.hamcrest.Matchers.endsWith("/c.png")))
                .andReturn().getResponse().getContentAsString();
        JsonNode urls = json.readTree(body).get("urls");

        // Le frontend enregistre le produit avec les seules URLs : elles sont reconnues et rangées dans le dossier du produit
        mvc.perform(post("/api/admin/categories").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Téléphones\"}"));
        String product = json.writeValueAsString(Map.of("name", "Téléphone Test", "category", "telephones",
                "brand", "Tecno", "price", 100_000, "stock", 3,
                "images", List.of(urls.get(1).asText(), urls.get(0).asText())));
        mvc.perform(post("/api/admin/products").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageDetails[0].publicId").value("hitech-dz/products/telephone-test/b"))
                .andExpect(jsonPath("$.imageDetails[1].publicId").value("hitech-dz/products/telephone-test/a"));
        verify(storage).move("hitech-dz/products/b", "hitech-dz/products/telephone-test");
        verify(storage).move("hitech-dz/products/a", "hitech-dz/products/telephone-test");
    }

    @Test
    void security_401_without_token_403_for_customer() throws Exception {
        mvc.perform(upload(null, file("a.png", "image/png", png(100))))
                .andExpect(status().isUnauthorized());
        String customer = token("client.upload@example.cm", true);
        mvc.perform(upload(customer, file("a.png", "image/png", png(100))))
                .andExpect(status().isForbidden());
        verify(storage, never()).upload(any(), anyString());
    }

    @Test
    void validation_errors() throws Exception {
        String admin = token("admin@hitech-dz.cm", false);

        mvc.perform(upload(admin, file("doc.pdf", "application/pdf", "%PDF-1.7 contenu".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Format non supporté (JPEG, PNG ou WebP)."))
                .andExpect(jsonPath("$.fieldErrors.files").exists());
        // Extension et type trompeurs : les octets ne sont pas ceux d'une image
        mvc.perform(upload(admin, file("faux.png", "image/png", "%PDF-1.7 contenu".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Format non supporté (JPEG, PNG ou WebP)."));

        mvc.perform(upload(admin, file("big.png", "image/png", png(6 * 1024 * 1024))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("L'image dépasse 5 Mo."));

        MockMultipartFile[] nine = new MockMultipartFile[9];
        for (int i = 0; i < 9; i++) nine[i] = file("p" + i + ".png", "image/png", png(100));
        mvc.perform(upload(admin, nine))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("8 images maximum par envoi."));

        mvc.perform(upload(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Aucun fichier reçu."));

        verify(storage, never()).upload(any(), anyString());
    }

    @Test
    void cloudinary_failure_gives_502() throws Exception {
        String admin = token("admin@hitech-dz.cm", false);
        when(storage.upload(any(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "L'envoi de l'image a échoué. Réessayez."));
        mvc.perform(upload(admin, file("a.png", "image/png", png(100))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("L'envoi de l'image a échoué. Réessayez."));
    }

    @Test
    void cors_preflight_without_token_for_lovable_origins() throws Exception {
        for (String origin : List.of("https://hitech-momo-market.lovable.app",
                "https://id-preview--1234abcd.lovable.app")) {
            mvc.perform(options("/api/admin/uploads")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        mvc.perform(options("/api/admin/uploads")
                        .header("Origin", "https://site-pirate.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        assertThat(counter.get()).isZero();
    }
}
