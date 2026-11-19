package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.WatermarkSettingsDto;
import cm.hitechdz.auth.dto.content.WatermarkStatusDto;
import cm.hitechdz.auth.service.media.WatermarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/watermark")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Logo sur les photos (admin)", description = "Logo HITECH DZ incrusté automatiquement sur les nouvelles photos produits")
public class AdminWatermarkController {

    private final WatermarkService service;

    public AdminWatermarkController(WatermarkService service) {
        this.service = service;
    }

    @GetMapping
    public WatermarkStatusDto get() {
        return service.status();
    }

    @PutMapping
    @Operation(summary = "Activer/désactiver et régler position, taille (% largeur), marge (%) et opacité (%)")
    public WatermarkStatusDto update(@Valid @RequestBody WatermarkSettingsDto request) {
        return service.update(request);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Remplacer le logo (PNG ou WebP transparent, 300 px de large minimum, 2 Mo maximum)")
    public WatermarkStatusDto replaceLogo(@RequestPart(value = "file", required = false) MultipartFile file) {
        return service.replaceLogo(file);
    }

    @DeleteMapping("/logo")
    @Operation(summary = "Revenir au logo HITECH DZ intégré à l'application")
    public WatermarkStatusDto resetLogo() {
        return service.resetLogo();
    }
}
