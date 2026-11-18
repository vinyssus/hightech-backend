package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.PublicBannerDto;
import cm.hitechdz.auth.service.content.HomeBannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Bannières d'accueil", description = "Affiches visibles de la page d'accueil (public)")
public class HomeBannerController {

    private final HomeBannerService service;

    public HomeBannerController(HomeBannerService service) {
        this.service = service;
    }

    @GetMapping("/api/banners/home")
    @Operation(summary = "Affiches visibles, groupées par emplacement",
            description = "Toujours les clés MAIN, SIDE_TOP et SIDE_BOTTOM (tableaux vides possibles), triées par position. "
                    + "Cache-Control: public, max-age=60.")
    public ResponseEntity<Map<String, List<PublicBannerDto>>> home() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(service.publicHome());
    }
}
