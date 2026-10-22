package cm.hitechdz.auth.controller.catalog;

import cm.hitechdz.auth.dto.catalog.BrandDto;
import cm.hitechdz.auth.dto.catalog.CartValidateRequest;
import cm.hitechdz.auth.dto.catalog.CartValidateResponse;
import cm.hitechdz.auth.dto.catalog.CatalogPage;
import cm.hitechdz.auth.dto.catalog.CategoryDto;
import cm.hitechdz.auth.dto.catalog.HelpfulResponse;
import cm.hitechdz.auth.dto.catalog.ProductDetail;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.dto.catalog.ReviewDto;
import cm.hitechdz.auth.dto.catalog.ReviewPage;
import cm.hitechdz.auth.dto.catalog.ReviewRequest;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.service.catalog.BrandService;
import cm.hitechdz.auth.service.catalog.CatalogQueryService;
import cm.hitechdz.auth.service.catalog.CategoryService;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalogue public (sans connexion), sauf la publication d'avis et le vote « utile ». */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogQueryService catalog;
    private final CategoryService categoryService;
    private final BrandService brandService;
    private final ReviewService reviewService;
    private final InventoryService inventoryService;

    public CatalogController(CatalogQueryService catalog, CategoryService categoryService, BrandService brandService,
                             ReviewService reviewService, InventoryService inventoryService) {
        this.catalog = catalog;
        this.categoryService = categoryService;
        this.brandService = brandService;
        this.reviewService = reviewService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/products")
    public CatalogPage products(@RequestParam(required = false) String q,
                                @RequestParam(required = false) String cat,
                                @RequestParam(required = false) List<String> brands,
                                @RequestParam(required = false) Long min,
                                @RequestParam(required = false) Long max,
                                @RequestParam(required = false) Boolean promo,
                                @RequestParam(required = false) Double rating,
                                @RequestParam(required = false) Boolean inStock,
                                @RequestParam(required = false) String sort,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "" + CatalogQueryService.DEFAULT_PAGE_SIZE) int size) {
        return catalog.search(CatalogQueryService.filter(q, cat, brands, min, max, promo, rating, inStock),
                sort, page, size);
    }

    @GetMapping("/products/flash")
    public List<ProductSummary> flash() {
        return catalog.flash();
    }

    @GetMapping("/products/new")
    public List<ProductSummary> newArrivals() {
        return catalog.newArrivals();
    }

    @GetMapping("/products/best-sellers")
    public List<ProductSummary> bestSellers() {
        return catalog.bestSellers();
    }

    @GetMapping("/products/search")
    public List<ProductSummary> suggestions(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "6") int limit) {
        return catalog.suggestions(q, limit);
    }

    @GetMapping("/products/{slug}")
    public ProductDetail product(@PathVariable String slug) {
        return catalog.detail(slug);
    }

    @GetMapping("/products/{slug}/reviews")
    public ReviewPage reviews(@PathVariable String slug,
                              @RequestParam(required = false) String sort,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "10") int size) {
        return reviewService.list(slug, sort, page, size);
    }

    @PostMapping("/products/{slug}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto addReview(@PathVariable String slug,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               @Valid @RequestBody ReviewRequest request) {
        return reviewService.create(slug, user.id(), request);
    }

    @PostMapping("/reviews/{id}/helpful")
    public HelpfulResponse helpful(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return reviewService.markHelpful(id, user.id());
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return categoryService.publicList();
    }

    @GetMapping("/brands")
    public List<BrandDto> brands(@RequestParam(required = false) String cat,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) Long min,
                                 @RequestParam(required = false) Long max,
                                 @RequestParam(required = false) Boolean promo,
                                 @RequestParam(required = false) Double rating,
                                 @RequestParam(required = false) Boolean inStock) {
        return brandService.publicList(CatalogQueryService.filter(q, cat, null, min, max, promo, rating, inStock));
    }

    /** Recalcule le panier côté serveur : le prix envoyé par le navigateur n'est jamais pris en compte. */
    @PostMapping("/cart/validate")
    public CartValidateResponse validateCart(@Valid @RequestBody CartValidateRequest request) {
        return inventoryService.validateCart(request.items());
    }
}
