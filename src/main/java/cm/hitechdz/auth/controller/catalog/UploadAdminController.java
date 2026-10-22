package cm.hitechdz.auth.controller.catalog;

import cm.hitechdz.auth.service.catalog.ProductAdminService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Envoi des photos du formulaire produit (admin).
 * POST /api/admin/uploads, multipart, champ « files » répété → { "urls": [...] } dans l'ordre des fichiers.
 * Les images sont rangées dans hitech-dz/products puis déplacées dans le dossier du produit à son enregistrement ;
 * celles qui ne sont jamais rattachées sont supprimées après 24 h.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class UploadAdminController {

    public record UploadResponse(List<String> urls) {}

    private final ProductAdminService products;

    public UploadAdminController(ProductAdminService products) {
        this.products = products;
    }

    @PostMapping(value = "/api/admin/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam(name = "files", required = false) List<MultipartFile> files) {
        return new UploadResponse(products.uploadForForm(files));
    }
}
