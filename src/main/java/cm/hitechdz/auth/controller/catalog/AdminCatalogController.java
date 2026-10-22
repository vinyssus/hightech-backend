package cm.hitechdz.auth.controller.catalog;

import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.catalog.AdminCategoryDto;
import cm.hitechdz.auth.dto.catalog.AdminProduct;
import cm.hitechdz.auth.dto.catalog.AdminReviewDto;
import cm.hitechdz.auth.dto.catalog.BrandDto;
import cm.hitechdz.auth.dto.catalog.CategoryRequest;
import cm.hitechdz.auth.dto.catalog.ImageOrderRequest;
import cm.hitechdz.auth.dto.catalog.ProductRequest;
import cm.hitechdz.auth.dto.catalog.UploadedImage;
import cm.hitechdz.auth.dto.catalog.VariantsRequest;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.service.catalog.BrandService;
import cm.hitechdz.auth.service.catalog.CategoryService;
import cm.hitechdz.auth.service.catalog.ProductAdminService;
import cm.hitechdz.auth.service.catalog.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Administration du catalogue. Double protection :
 * règle d'URL /api/admin/** dans SecurityConfig + @PreAuthorize au niveau de la classe.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

    private static final String IMAGES_SEGMENT = "/images/";

    private final ProductAdminService products;
    private final CategoryService categories;
    private final BrandService brands;
    private final ReviewService reviews;

    public AdminCatalogController(ProductAdminService products, CategoryService categories,
                                  BrandService brands, ReviewService reviews) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.reviews = reviews;
    }

    // ------------------------------------------------------------------ produits
    @GetMapping("/products")
    public PageResponse<AdminProduct> products(@RequestParam(required = false) String search,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return products.list(search, category, status, page, size);
    }

    @GetMapping("/products/low-stock")
    public List<AdminProduct> lowStock() {
        return products.lowStock();
    }

    @GetMapping("/products/slug/{slug}")
    public AdminProduct productBySlug(@PathVariable String slug) {
        return products.getBySlug(slug);
    }

    @GetMapping("/products/{id}")
    public AdminProduct product(@PathVariable String id) {
        return products.get(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProduct create(@Valid @RequestBody ProductRequest request) {
        return products.create(request);
    }

    @PutMapping("/products/{id}")
    public AdminProduct update(@PathVariable String id, @Valid @RequestBody ProductRequest request) {
        return products.update(id, request);
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        products.delete(id);
    }

    @PutMapping("/products/{id}/variants")
    public AdminProduct variants(@PathVariable String id, @Valid @RequestBody VariantsRequest request) {
        return products.replaceVariants(id, request.combinations());
    }

    // ------------------------------------------------------------------ images
    @PostMapping(path = "/uploads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<UploadedImage> upload(@RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return products.uploadTmp(files);
    }

    @PostMapping(path = "/products/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminProduct addImages(@PathVariable String id,
                                  @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return products.addImages(id, files);
    }

    @PutMapping("/products/{id}/images/order")
    public AdminProduct reorderImages(@PathVariable String id, @Valid @RequestBody ImageOrderRequest request) {
        return products.reorderImages(id, request.publicIds());
    }

    /**
     * Le publicId contient des « / » (hitech-dz/products/slug/xxx) : il est accepté encodé
     * (%2F) ou non, et lu dans la fin du chemin.
     */
    @DeleteMapping("/products/{id}/images/**")
    public AdminProduct removeImage(@PathVariable String id, HttpServletRequest request) {
        String uri = request.getRequestURI();
        int start = uri.indexOf(IMAGES_SEGMENT);
        String raw = start < 0 ? "" : uri.substring(start + IMAGES_SEGMENT.length());
        String publicId = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if (publicId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Identifiant d'image manquant.");
        }
        return products.removeImage(id, publicId);
    }

    // ------------------------------------------------------------------ catégories
    @GetMapping("/categories")
    public List<AdminCategoryDto> categories() {
        return categories.adminList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCategoryDto createCategory(@Valid @RequestBody CategoryRequest request) {
        return categories.create(request);
    }

    @PutMapping("/categories/{slug}")
    public AdminCategoryDto updateCategory(@PathVariable String slug, @Valid @RequestBody CategoryRequest request) {
        return categories.update(slug, request);
    }

    @DeleteMapping("/categories/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String slug) {
        categories.delete(slug);
    }

    // ------------------------------------------------------------------ marques
    @GetMapping("/brands")
    public List<BrandDto> brands() {
        return brands.adminList();
    }

    /** Corps multipart : name + logo optionnel (champ « logo », « file », « image » ou « files »). */
    @PostMapping(path = "/brands", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BrandDto createBrandMultipart(MultipartHttpServletRequest request) {
        return brands.create(request.getParameter("name"), firstFile(request),
                request.getParameter("logoUrl"), request.getParameter("logoPublicId"));
    }

    /** Corps JSON : { name, logo? (URL https), logoPublicId? (image envoyée via /uploads/images) }. */
    @PostMapping(path = "/brands", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BrandDto createBrandJson(@RequestBody BrandRequest request) {
        return brands.create(request.name(), null, request.logo(), request.logoPublicId());
    }

    @PutMapping(path = "/brands/{slug}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BrandDto updateBrandMultipart(@PathVariable String slug, MultipartHttpServletRequest request) {
        return brands.update(slug, request.getParameter("name"), firstFile(request),
                request.getParameter("logoUrl"), request.getParameter("logoPublicId"));
    }

    @PutMapping(path = "/brands/{slug}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BrandDto updateBrandJson(@PathVariable String slug, @RequestBody BrandRequest request) {
        return brands.update(slug, request.name(), null, request.logo(), request.logoPublicId());
    }

    @DeleteMapping("/brands/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBrand(@PathVariable String slug) {
        brands.delete(slug);
    }

    public record BrandRequest(String name, String logo, String logoPublicId) {}

    /** Premier fichier non vide, quel que soit le nom du champ utilisé par le formulaire. */
    private static MultipartFile firstFile(MultipartHttpServletRequest request) {
        for (String field : List.of("logo", "file", "image", "files")) {
            MultipartFile f = request.getFile(field);
            if (f != null && !f.isEmpty()) {
                return f;
            }
        }
        return request.getFileMap().values().stream().filter(f -> !f.isEmpty()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ avis
    @GetMapping("/reviews")
    public PageResponse<AdminReviewDto> reviews(@RequestParam(required = false) String productId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return reviews.adminList(productId, page, size);
    }

    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReview(@PathVariable Long id) {
        reviews.delete(id);
    }
}
