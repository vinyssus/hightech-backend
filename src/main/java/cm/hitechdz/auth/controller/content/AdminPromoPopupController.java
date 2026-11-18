package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.AdminPromoAdDto;
import cm.hitechdz.auth.dto.content.PromoActiveRequest;
import cm.hitechdz.auth.dto.content.PromoReorderRequest;
import cm.hitechdz.auth.dto.content.PromoSettingsDto;
import cm.hitechdz.auth.service.content.PromoPopupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/promo-popup")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Panneau publicitaire (admin)", description = "Publicités et réglages du panneau flottant (rôle ADMIN)")
public class AdminPromoPopupController {

    private final PromoPopupService service;

    public AdminPromoPopupController(PromoPopupService service) {
        this.service = service;
    }

    @GetMapping("/ads")
    @Operation(summary = "Toutes les publicités, avec statut calculé LIVE, SCHEDULED, EXPIRED ou DISABLED")
    public List<AdminPromoAdDto> list() {
        return service.adminList();
    }

    @GetMapping("/ads/{id}")
    public AdminPromoAdDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping(value = "/ads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer une publicité",
            description = "multipart/form-data : image (obligatoire), mobileImage (facultatif), data (JSON : altText, linkType, "
                    + "linkValue, position, active, startsAt, endsAt). 400, 409 (6 actives), 413, 415.")
    public AdminPromoAdDto create(@RequestPart(value = "image", required = false) MultipartFile image,
                                  @RequestPart(value = "mobileImage", required = false) MultipartFile mobileImage,
                                  @RequestPart(value = "data", required = false) String data) {
        return service.create(data, image, mobileImage);
    }

    @PutMapping(value = "/ads/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Modifier une publicité", description = "data obligatoire ; images facultatives (les anciennes sont gardées si absentes).")
    public AdminPromoAdDto update(@PathVariable UUID id,
                                  @RequestPart(value = "image", required = false) MultipartFile image,
                                  @RequestPart(value = "mobileImage", required = false) MultipartFile mobileImage,
                                  @RequestPart(value = "data", required = false) String data) {
        return service.update(id, data, image, mobileImage);
    }

    @PatchMapping("/ads/{id}/active")
    public AdminPromoAdDto setActive(@PathVariable UUID id, @Valid @RequestBody PromoActiveRequest request) {
        return service.setActive(id, request.active());
    }

    @PutMapping("/ads/reorder")
    @Operation(summary = "Réordonner", description = "{\"ids\": [...]} avec toutes les publicités.")
    public List<AdminPromoAdDto> reorder(@Valid @RequestBody PromoReorderRequest request) {
        return service.reorder(request.ids());
    }

    @DeleteMapping("/ads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/settings")
    public PromoSettingsDto settings() {
        return service.adminSettings();
    }

    @PutMapping("/settings")
    public PromoSettingsDto updateSettings(@Valid @RequestBody PromoSettingsDto request) {
        return service.updateSettings(request);
    }
}
