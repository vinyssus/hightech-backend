package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import cm.hitechdz.auth.dto.catalog.BrandFacet;
import cm.hitechdz.auth.dto.catalog.CatalogPage;
import cm.hitechdz.auth.dto.catalog.Facets;
import cm.hitechdz.auth.dto.catalog.ProductDetail;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.BrandRepository;
import cm.hitechdz.auth.repository.catalog.ProductFilter;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ProductSearchRepository;
import cm.hitechdz.auth.repository.catalog.ProductSearchRepository.SortMode;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lecture du catalogue public : seuls les produits publiés et non supprimés sont visibles. */
@Service
public class CatalogQueryService {

    static final String PRODUCT_NOT_FOUND = "Produit introuvable.";
    static final String INVALID_SORT = "Tri invalide (pertinence, prix-asc, prix-desc, nouveautes ou popularite).";

    public static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_PAGE_SIZE = 60;
    private static final int HOME_LIST_SIZE = 8;
    private static final int FLASH_SIZE = 6;

    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;
    private final BrandRepository brandRepository;

    public CatalogQueryService(ProductRepository productRepository, ProductSearchRepository searchRepository,
                               BrandRepository brandRepository) {
        this.productRepository = productRepository;
        this.searchRepository = searchRepository;
        this.brandRepository = brandRepository;
    }

    /** Construit le filtre à partir des paramètres bruts de l'URL. */
    public static ProductFilter filter(String q, String cat, List<String> brands, Long min, Long max,
                                       Boolean promo, Double rating, Boolean inStock) {
        String query = q == null ? null : SlugUtil.normalize(q);
        List<String> brandKeys = brands == null ? List.of() : brands.stream()
                .filter(Objects::nonNull)
                .flatMap(b -> Arrays.stream(b.split(",")))
                .map(Brand::keyOf)
                .filter(b -> !b.isEmpty())
                .distinct()
                .toList();
        return new ProductFilter(
                query == null || query.isBlank() ? null : query,
                cat == null || cat.isBlank() ? null : cat.trim(),
                brandKeys, min, max,
                Boolean.TRUE.equals(promo), rating, Boolean.TRUE.equals(inStock));
    }

    public static SortMode sortMode(String sort) {
        if (sort == null || sort.isBlank()) {
            return SortMode.PERTINENCE;
        }
        return switch (sort.trim()) {
            case "pertinence" -> SortMode.PERTINENCE;
            case "prix-asc" -> SortMode.PRICE_ASC;
            case "prix-desc" -> SortMode.PRICE_DESC;
            case "nouveautes" -> SortMode.NEWEST;
            case "popularite" -> SortMode.POPULARITY;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_SORT);
        };
    }

    @Transactional(readOnly = true)
    public CatalogPage search(ProductFilter filter, String sort, int page, int size) {
        SortMode mode = sortMode(sort);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);

        Page<Product> result = searchRepository.search(filter, mode, pageable);
        List<ProductSummary> content = result.getContent().stream().map(CatalogMapper::toSummary).toList();

        // Facettes : marques comptées sans le filtre marque ; bornes de prix sans le filtre prix
        Map<String, Long> counts = searchRepository.countByBrand(filter.withoutBrands());
        List<BrandFacet> brandFacets = brandRepository.findAllOrdered().stream()
                .filter(b -> counts.containsKey(b.getNameKey()))
                .map(b -> new BrandFacet(b.getName(), counts.get(b.getNameKey())))
                .toList();
        ProductSearchRepository.PriceRange range = searchRepository.priceRange(filter.withoutPrice());

        return new CatalogPage(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), new Facets(brandFacets, range.min(), range.max()));
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(String slug) {
        Product p = productRepository.findVisibleBySlug(slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, PRODUCT_NOT_FOUND));
        return CatalogMapper.toDetail(p);
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> flash() {
        return summaries(productRepository.findFlash(PageRequest.of(0, FLASH_SIZE)));
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> newArrivals() {
        return summaries(productRepository.findNewArrivals(PageRequest.of(0, HOME_LIST_SIZE)));
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> bestSellers() {
        return summaries(productRepository.findBestSellers(PageRequest.of(0, HOME_LIST_SIZE)));
    }

    /** Suggestions de recherche : nom, marque, catégorie et sous-catégories. */
    @Transactional(readOnly = true)
    public List<ProductSummary> suggestions(String q, int limit) {
        String query = q == null ? "" : SlugUtil.normalize(q);
        if (query.isBlank()) {
            return List.of();
        }
        ProductFilter filter = new ProductFilter(query, null, List.of(), null, null, false, null, false);
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        return summaries(searchRepository.search(filter, SortMode.PERTINENCE, PageRequest.of(0, safeLimit))
                .getContent());
    }

    private static List<ProductSummary> summaries(List<Product> products) {
        return products.stream().map(CatalogMapper::toSummary).toList();
    }
}
