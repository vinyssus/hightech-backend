package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.PromoPopupResponse;
import cm.hitechdz.auth.dto.content.PromoPopupSpecs;
import cm.hitechdz.auth.service.content.PromoPopupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/promo-popup")
@Tag(name = "Panneau publicitaire", description = "Panneau flottant en bas à gauche du site (public)")
public class PromoPopupController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final PromoPopupService service;
    private final ObjectMapper json;

    public PromoPopupController(PromoPopupService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "Réglages + publicités visibles maintenant",
            description = "ads vaut [] si le panneau est désactivé ou sans publicité. Cache-Control: public, max-age=60 ; "
                    + "ETag (If-None-Match → 304).")
    public ResponseEntity<PromoPopupResponse> get(HttpServletRequest request) throws Exception {
        PromoPopupResponse body = service.publicView();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(body));
        String etag = "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch != null && (ifNoneMatch.trim().equals("*")
                || java.util.Arrays.stream(ifNoneMatch.split(",")).map(String::trim)
                        .map(t -> t.startsWith("W/") ? t.substring(2) : t).anyMatch(etag::equals))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CACHE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CACHE).body(body);
    }

    @GetMapping("/specs")
    @Operation(summary = "Règles des images et bornes des réglages, pour valider avant l'envoi")
    public ResponseEntity<PromoPopupSpecs> specs() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(service.specs());
    }
}
