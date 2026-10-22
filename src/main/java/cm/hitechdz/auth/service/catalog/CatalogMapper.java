package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductImage;
import cm.hitechdz.auth.domain.catalog.ProductVariant;
import cm.hitechdz.auth.dto.catalog.AdminCategoryDto;
import cm.hitechdz.auth.dto.catalog.AdminCombination;
import cm.hitechdz.auth.dto.catalog.AdminProduct;
import cm.hitechdz.auth.dto.catalog.CategoryDto;
import cm.hitechdz.auth.dto.catalog.Combination;
import cm.hitechdz.auth.dto.catalog.ImageDetail;
import cm.hitechdz.auth.dto.catalog.ProductDetail;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.dto.catalog.SpecDto;
import cm.hitechdz.auth.dto.catalog.VariantGroup;
import cm.hitechdz.auth.dto.catalog.VariantOption;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Conversion entités → DTO (les entités ne sortent jamais des services). */
public final class CatalogMapper {

    private static final String CLOUDINARY_UPLOAD = "/image/upload/";
    private static final String DELIVERY_TRANSFORMATION = "f_auto,q_auto/";

    private CatalogMapper() {}

    /** URL de diffusion : ajoute f_auto,q_auto (WebP/AVIF selon le navigateur) aux images Cloudinary. */
    public static String deliveryUrl(String url) {
        if (url == null || !url.contains("res.cloudinary.com") || !url.contains(CLOUDINARY_UPLOAD)
                || url.contains(CLOUDINARY_UPLOAD + DELIVERY_TRANSFORMATION)) {
            return url;
        }
        return url.replace(CLOUDINARY_UPLOAD, CLOUDINARY_UPLOAD + DELIVERY_TRANSFORMATION);
    }

    public static String mainImage(Product p) {
        return p.getImages().isEmpty() ? null : deliveryUrl(p.getImages().get(0).getUrl());
    }

    public static ProductSummary toSummary(Product p) {
        return new ProductSummary(p.getId(), p.getSlug(), p.getName(), brandName(p), categorySlug(p),
                p.getPrice(), p.getOldPrice(), p.getRating(), p.getReviewsCount(), mainImage(p),
                p.getStock(), p.isNewProduct(), p.getSold());
    }

    public static ProductDetail toDetail(Product p) {
        return new ProductDetail(p.getId(), p.getSlug(), p.getName(), brandName(p), categorySlug(p),
                p.getPrice(), p.getOldPrice(), p.getRating(), p.getReviewsCount(), mainImage(p),
                p.getStock(), p.isNewProduct(), p.getSold(),
                imageUrls(p), p.getDescription(), specs(p), variantGroups(p), combinations(p));
    }

    public static AdminProduct toAdmin(Product p) {
        List<ImageDetail> details = p.getImages().stream()
                .map(i -> new ImageDetail(i.getPublicId(), deliveryUrl(i.getUrl()), i.getPosition(),
                        i.getWidth(), i.getHeight()))
                .toList();
        List<AdminCombination> combinations = p.getVariants().stream()
                .map(v -> new AdminCombination(v.getSku(), v.getColor(), v.getColorHex(), v.getCapacity(),
                        v.getRam(), v.getPriceDelta(), p.getPrice() + v.getPriceDelta(), v.getStock()))
                .toList();
        return new AdminProduct(p.getId(), p.getSlug(), p.getName(), brandName(p), categorySlug(p),
                p.getPrice(), p.getOldPrice(), p.getRating(), p.getReviewsCount(), mainImage(p),
                p.getStock(), p.isNewProduct(), p.getSold(),
                imageUrls(p), details, p.getDescription(), specs(p), p.getStatus().json(), combinations,
                p.getCreatedAt(), p.getUpdatedAt());
    }

    public static CategoryDto toDto(Category c) {
        return new CategoryDto(c.getSlug(), c.getName(), c.getIcon(), List.copyOf(c.getSub()));
    }

    public static AdminCategoryDto toAdminDto(Category c, long productCount) {
        return new AdminCategoryDto(c.getSlug(), c.getName(), c.getIcon(), List.copyOf(c.getSub()), productCount);
    }

    // ------------------------------------------------------------------ variantes
    /**
     * Groupes affichés sur la fiche. Pour chaque option : delta = plus petit écart de prix des
     * combinaisons qui la contiennent ; available = au moins une de ces combinaisons est en stock.
     */
    static List<VariantGroup> variantGroups(Product p) {
        List<VariantGroup> groups = new ArrayList<>();
        addGroup(groups, "Couleur", p.getVariants(), ProductVariant::getColor, true);
        addGroup(groups, "Capacité", p.getVariants(), ProductVariant::getCapacity, false);
        addGroup(groups, "RAM", p.getVariants(), ProductVariant::getRam, false);
        return groups;
    }

    private static void addGroup(List<VariantGroup> groups, String name, List<ProductVariant> variants,
                                 Function<ProductVariant, String> value, boolean withHex) {
        Map<String, long[]> deltas = new LinkedHashMap<>();       // label -> [delta minimal]
        Map<String, Boolean> available = new LinkedHashMap<>();
        Map<String, String> hex = new LinkedHashMap<>();
        for (ProductVariant v : variants) {
            String label = value.apply(v);
            if (label == null || label.isBlank()) {
                continue;
            }
            deltas.merge(label, new long[]{v.getPriceDelta()},
                    (a, b) -> new long[]{Math.min(a[0], b[0])});
            available.merge(label, v.getStock() > 0, Boolean::logicalOr);
            if (withHex && v.getColorHex() != null) {
                hex.putIfAbsent(label, v.getColorHex());
            }
        }
        if (deltas.isEmpty()) {
            return;
        }
        List<VariantOption> options = new ArrayList<>();
        deltas.forEach((label, d) -> options.add(
                new VariantOption(label, withHex ? hex.get(label) : null, d[0], available.get(label))));
        groups.add(new VariantGroup(name, options));
    }

    static List<Combination> combinations(Product p) {
        return p.getVariants().stream()
                .map(v -> new Combination(v.getSku(), v.getColor(), v.getCapacity(), v.getRam(),
                        p.getPrice() + v.getPriceDelta(), v.getStock()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers
    private static List<String> imageUrls(Product p) {
        return p.getImages().stream().map(ProductImage::getUrl).map(CatalogMapper::deliveryUrl).toList();
    }

    private static List<SpecDto> specs(Product p) {
        return p.getSpecs().stream().map(s -> new SpecDto(s.getKey(), s.getValue())).toList();
    }

    private static String brandName(Product p) {
        Brand b = p.getBrand();
        return b == null ? null : b.getName();
    }

    private static String categorySlug(Product p) {
        Category c = p.getCategory();
        return c == null ? null : c.getSlug();
    }
}
