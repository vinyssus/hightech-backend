package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.AdminBannerDto;
import cm.hitechdz.auth.dto.content.BannerActiveRequest;
import cm.hitechdz.auth.dto.content.BannerReorderRequest;
import cm.hitechdz.auth.dto.content.BannerSpecs;
import cm.hitechdz.auth.service.content.HomeBannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/banners")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Bannières d'accueil (admin)", description = "Gestion des affiches de la page d'accueil (rôle ADMIN)")
public class AdminHomeBannerController {

    private final HomeBannerService service;

    public AdminHomeBannerController(HomeBannerService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Toutes les affiches (actives, inactives, programmées, expirées) avec leur statut calculé")
    public List<AdminBannerDto> list(@RequestParam(required = false) String slot,
                                     @RequestParam(required = false) Boolean active) {
        return service.adminList(slot, active);
    }

    @GetMapping("/specs")
    @Operation(summary = "Normes d'image (formats, poids, dimensions, ratio, maximum par emplacement)")
    public BannerSpecs specs() {
        return service.specs();
    }

    @GetMapping("/{id}")
    public AdminBannerDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer une affiche",
            description = "multipart/form-data : image (obligatoire), mobileImage (facultatif), data (JSON : slot, altText, "
                    + "linkType, linkValue, position, active, startsAt, endsAt). 400 validation, 409 limite atteinte, "
                    + "413 fichier > 5 Mo, 415 format refusé.")
    public AdminBannerDto create(@Parameter(description = "Image principale") @RequestPart(value = "image", required = false) MultipartFile image,
                                 @RequestPart(value = "mobileImage", required = false) MultipartFile mobileImage,
                                 @Parameter(description = "JSON des champs") @RequestPart(value = "data", required = false) String data) {
        return service.create(data, image, mobileImage);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Modifier une affiche",
            description = "data obligatoire ; image et mobileImage facultatifs (remplacement, l'ancienne image est supprimée). "
                    + "data.removeMobileImage=true retire la version mobile.")
    public AdminBannerDto update(@PathVariable Long id,
                                 @RequestPart(value = "image", required = false) MultipartFile image,
                                 @RequestPart(value = "mobileImage", required = false) MultipartFile mobileImage,
                                 @RequestPart(value = "data", required = false) String data) {
        return service.update(id, data, image, mobileImage);
    }

    @PatchMapping("/{id}/active")
    public AdminBannerDto setActive(@PathVariable Long id, @Valid @RequestBody BannerActiveRequest request) {
        return service.setActive(id, request.active());
    }

    @PutMapping("/reorder")
    @Operation(summary = "Réordonner un emplacement", description = "{\"slot\":\"MAIN\",\"ids\":[3,1,2]} avec toutes les affiches de l'emplacement.")
    public List<AdminBannerDto> reorder(@Valid @RequestBody BannerReorderRequest request) {
        return service.reorder(request.slot(), request.ids());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
