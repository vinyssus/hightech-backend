package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.PublicAnnouncementDto;
import cm.hitechdz.auth.service.content.AnnouncementService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Bandeau d'annonces : public, sans authentification. */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    /** Annonces visibles maintenant ; [] s'il n'y en a aucune. Cache court (60 s). */
    @GetMapping
    public ResponseEntity<List<PublicAnnouncementDto>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(service.visible());
    }
}
