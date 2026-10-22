package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductImage;
import cm.hitechdz.auth.domain.catalog.ProductSpec;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import cm.hitechdz.auth.domain.catalog.ProductVariant;
import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.catalog.AdminProduct;
import cm.hitechdz.auth.dto.catalog.ImageInput;
import cm.hitechdz.auth.dto.catalog.ProductRequest;
import cm.hitechdz.auth.dto.catalog.SpecDto;
import cm.hitechdz.auth.dto.catalog.UploadedImage;
import cm.hitechdz.auth.dto.catalog.VariantInput;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ProductVariantRepository;
import cm.hitechdz.auth.repository.order.OrderItemRepository;
import cm.hitechdz.auth.service.media.ImageDeletionRequested;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.ImageUploadService;
import cm.hitechdz.auth.service.media.StoredImage;
import cm.hitechdz.auth.util.SlugUtil;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Gestion des produits par l'administrateur (brouillons compris). */
@Service
public class ProductAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);

    public static final int LOW_STOCK_THRESHOLD = 5;
    static final String NOT_FOUND = "Produit introuvable.";
    static final String INVALID_FIELDS = "Certains champs sont invalides.";
    static final String UNKNOWN_CATEGORY = "Cette catégorie n'existe pas.";
    static final String OLD_PRICE = "L'ancien prix doit être supérieur au prix.";
    static final String IMAGE_HTTPS = "Chaque image doit être une URL https.";
    static final String IMAGE_UNKNOWN = "Image inconnue : envoyez-la d'abord via /api/admin/uploads/images.";
    static final String BAD_STATUS = "Statut invalide (published ou draft).";
    static final String IMAGE_NOT_FOUND = "Cette image n'appartient pas au produit.";
    static final String BAD_ORDER = "La liste doit contenir exactement les images du produit.";

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final OrderItemRepository orderItemRepository;
    private final CategoryService categoryService;
    private final BrandService brandService;
    private final ImageStorageService storage;
    private final ImageUploadService uploadService;
    private final ApplicationEventPublisher events;

    public ProductAdminService(ProductRepository productRepository, ProductVariantRepository variantRepository,
                               OrderItemRepository orderItemRepository, CategoryService categoryService,
                               BrandService brandService, ImageStorageService storage,
                               ImageUploadService uploadService, ApplicationEventPublisher events) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.orderItemRepository = orderItemRepository;
        this.categoryService = categoryService;
        this.brandService = brandService;
        this.storage = storage;
        this.uploadService = uploadService;
        this.events = events;
    }

    // ------------------------------------------------------------------ lecture
    @Transactional(readOnly = true)
    public PageResponse<AdminProduct> list(String search, String category, String status, int page, int size) {
        ProductStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            statusFilter = ProductStatus.fromJson(status);
            if (statusFilter == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, BAD_STATUS);
            }
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(productRepository
                .findAll(adminSpec(search, category, statusFilter), pageable)
                .map(CatalogMapper::toAdmin));
    }

    @Transactional(readOnly = true)
    public AdminProduct get(String id) {
        return CatalogMapper.toAdmin(load(id));
    }

    @Transactional(readOnly = true)
    public AdminProduct getBySlug(String slug) {
        return CatalogMapper.toAdmin(productRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public List<AdminProduct> lowStock() {
        return productRepository.findLowStock(LOW_STOCK_THRESHOLD).stream().map(CatalogMapper::toAdmin).toList();
    }

    // ------------------------------------------------------------------ création / modification
    @Transactional
    public AdminProduct create(ProductRequest req) {
        Validated v = validate(req, null);
        Product p = new Product();
        apply(p, req, v);
        p.setSlug(uniqueSlug(req.name(), p.getId(), null));
        p.setNewProduct(req.isNew() == null || req.isNew());
        p.setStock(req.stock());
        replaceImages(p, req.images(), List.of());
        SearchText.refresh(p);
        productRepository.saveAndFlush(p);
        log.info("Produit créé : {} ({})", p.getName(), p.getId());
        return CatalogMapper.toAdmin(p);
    }

    @Transactional
    public AdminProduct update(String id, ProductRequest req) {
        Product p = load(id);
        Validated v = validate(req, p);
        boolean renamed = !p.getName().equals(req.name().trim());
        apply(p, req, v);
        if (renamed) {
            p.setSlug(uniqueSlug(req.name(), p.getId(), p.getSlug()));
        }
        if (req.isNew() != null) {
            p.setNewProduct(req.isNew());
        }
        if (!p.hasVariants()) {
            p.setStock(req.stock());   // avec variantes, le stock est la somme des combinaisons
        }
        List<String> removed = replaceImages(p, req.images(), new ArrayList<>(p.getImages()));
        SearchText.refresh(p);
        productRepository.flush();
        requestDeletion(removed);
        return CatalogMapper.toAdmin(p);
    }

    /**
     * Suppression : logique (deletedAt) si le produit figure dans des commandes, pour ne pas casser
     * l'historique ; physique sinon. Les images Cloudinary sont supprimées dans les deux cas.
     */
    @Transactional
    public void delete(String id) {
        Product p = load(id);
        List<String> publicIds = publicIds(p.getImages());
        if (orderItemRepository.existsByProductId(p.getId())) {
            p.setDeletedAt(Instant.now());
            p.getImages().clear();
            productRepository.flush();
            log.info("Produit {} supprimé logiquement (présent dans des commandes)", p.getId());
        } else {
            productRepository.delete(p);
            productRepository.flush();
            log.info("Produit {} supprimé", p.getId());
        }
        requestDeletion(publicIds);
    }

    // ------------------------------------------------------------------ images
    /** Envoi d'images avant l'enregistrement du produit : dossier tmp, purgé après 24 h si non rattaché. */
    public List<UploadedImage> uploadTmp(List<MultipartFile> files) {
        return uploadService.uploadAll(files, ImageStorageService.TMP_FOLDER, 0).stream()
                .map(s -> new UploadedImage(CatalogMapper.deliveryUrl(s.url()), s.publicId(), s.width(), s.height()))
                .toList();
    }

    @Transactional
    public AdminProduct addImages(String id, List<MultipartFile> files) {
        Product p = load(id);
        List<StoredImage> stored = uploadService.uploadAll(files,
                ImageStorageService.productFolder(p.getSlug()), p.getImages().size());
        stored.forEach(s -> p.addImage(new ProductImage(s.url(), s.publicId(), s.width(), s.height())));
        productRepository.flush();
        return CatalogMapper.toAdmin(p);
    }

    @Transactional
    public AdminProduct removeImage(String id, String publicId) {
        Product p = load(id);
        ProductImage image = p.getImages().stream()
                .filter(i -> Objects.equals(i.getPublicId(), publicId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, IMAGE_NOT_FOUND));
        p.getImages().remove(image);
        p.renumberImages();
        productRepository.flush();
        requestDeletion(List.of(publicId));
        return CatalogMapper.toAdmin(p);
    }

    /** Réordonne les images ; la première devient l'image principale. */
    @Transactional
    public AdminProduct reorderImages(String id, List<String> publicIds) {
        Product p = load(id);
        Map<String, ProductImage> byPublicId = new LinkedHashMap<>();
        List<ProductImage> external = new ArrayList<>();
        for (ProductImage i : p.getImages()) {
            if (i.getPublicId() == null) external.add(i); else byPublicId.put(i.getPublicId(), i);
        }
        if (publicIds.size() != byPublicId.size() || !byPublicId.keySet().containsAll(publicIds)
                || new HashSet<>(publicIds).size() != publicIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, BAD_ORDER, Map.of("publicIds", BAD_ORDER));
        }
        List<ProductImage> ordered = new ArrayList<>();
        publicIds.forEach(pid -> ordered.add(byPublicId.get(pid)));
        ordered.addAll(external);
        p.getImages().clear();
        p.getImages().addAll(ordered);
        p.renumberImages();
        productRepository.flush();
        return CatalogMapper.toAdmin(p);
    }

    // ------------------------------------------------------------------ variantes
    /** Remplace toutes les combinaisons (les SKU existants sont mis à jour, les autres créés ou retirés). */
    @Transactional
    public AdminProduct replaceVariants(String id, List<VariantInput> inputs) {
        Product p = load(id);
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            VariantInput in = inputs.get(i);
            String sku = in.sku().trim();
            if (!seen.add(sku)) {
                errors.put("combinations[" + i + "].sku", "SKU en double : " + sku + ".");
            }
            long delta = in.priceDelta() == null ? 0 : in.priceDelta();
            if (p.getPrice() + delta <= 0) {
                errors.put("combinations[" + i + "].priceDelta", "Le prix final de la combinaison doit être supérieur à 0.");
            }
        }
        if (!seen.isEmpty()) {
            for (String used : variantRepository.findSkusUsedElsewhere(seen, p.getId())) {
                errors.put("sku", "SKU déjà utilisé par un autre produit : " + used + ".");
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FIELDS, errors);
        }

        Map<String, ProductVariant> existing = new HashMap<>();
        p.getVariants().forEach(v -> existing.put(v.getSku(), v));
        List<ProductVariant> next = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            VariantInput in = inputs.get(i);
            String sku = in.sku().trim();
            ProductVariant v = existing.remove(sku);
            if (v == null) {
                v = new ProductVariant();
                v.setSku(sku);
                p.addVariant(v);
            }
            v.setColor(blankToNull(in.color()));
            v.setColorHex(blankToNull(in.colorHex()));
            v.setCapacity(blankToNull(in.capacity()));
            v.setRam(blankToNull(in.ram()));
            v.setPriceDelta(in.priceDelta() == null ? 0 : in.priceDelta());
            v.setStock(in.stock());
            v.setPosition(i);
            next.add(v);
        }
        p.getVariants().removeAll(existing.values());   // combinaisons retirées
        p.getVariants().sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        p.recomputeStockFromVariants();
        productRepository.flush();
        return CatalogMapper.toAdmin(p);
    }

    // ------------------------------------------------------------------ helpers
    private record Validated(Category category, ProductStatus status) {}

    private Validated validate(ProductRequest req, Product current) {
        Map<String, String> errors = new LinkedHashMap<>();
        Category category = categoryService.findForProduct(req.category());
        if (category == null) {
            errors.put("category", UNKNOWN_CATEGORY);
        }
        if (req.oldPrice() != null && req.oldPrice() <= req.price()) {
            errors.put("oldPrice", OLD_PRICE);
        }
        ProductStatus status = ProductStatus.PUBLISHED;
        if (req.status() != null && !req.status().isBlank()) {
            status = ProductStatus.fromJson(req.status());
            if (status == null) {
                errors.put("status", BAD_STATUS);
            }
        }
        if (req.images() != null) {
            Set<String> ownPublicIds = current == null ? Set.of() : new HashSet<>(publicIds(current.getImages()));
            for (ImageInput img : req.images()) {
                if (img == null || img.url() == null || !img.url().startsWith("https://")) {
                    errors.put("images", IMAGE_HTTPS);
                    break;
                }
                String pid = img.publicId();
                if (pid != null && !pid.isBlank() && !ImageStorageService.isTmp(pid) && !ownPublicIds.contains(pid)) {
                    errors.put("images", IMAGE_UNKNOWN);
                    break;
                }
            }
        }
        if (current != null && current.hasVariants()) {
            for (ProductVariant v : current.getVariants()) {
                if (req.price() + v.getPriceDelta() <= 0) {
                    errors.put("price", "Le prix rendrait la combinaison " + v.getSku() + " gratuite ou négative.");
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FIELDS, errors);
        }
        return new Validated(category, status);
    }

    private void apply(Product p, ProductRequest req, Validated v) {
        p.setName(req.name().trim());
        p.setDescription(req.description() == null ? null : req.description().trim());
        p.setCategory(v.category());
        p.setBrand(brandService.resolveOrCreate(req.brand()));
        p.setPrice(req.price());
        p.setOldPrice(req.oldPrice());
        p.setStatus(v.status());
        p.getSpecs().clear();
        if (req.specs() != null) {
            for (SpecDto s : req.specs()) {
                p.getSpecs().add(new ProductSpec(s.key().trim(), s.value().trim()));
            }
        }
    }

    /**
     * Applique la liste d'images demandée : images existantes conservées, images tmp déplacées
     * dans le dossier du produit, URLs externes ajoutées.
     * @return publicIds des images retirées (à supprimer de Cloudinary après le commit)
     */
    private List<String> replaceImages(Product p, List<ImageInput> inputs, List<ProductImage> existing) {
        if (inputs == null) {
            return List.of();   // champ absent : images inchangées
        }
        Map<String, ProductImage> byPublicId = new HashMap<>();
        Map<String, ProductImage> externalByUrl = new HashMap<>();
        for (ProductImage i : existing) {
            if (i.getPublicId() != null) byPublicId.put(i.getPublicId(), i);
            else externalByUrl.putIfAbsent(i.getUrl(), i);
        }
        String folder = ImageStorageService.productFolder(p.getSlug());
        List<ProductImage> next = new ArrayList<>();
        Set<ProductImage> kept = new HashSet<>();
        for (ImageInput in : inputs) {
            String pid = in.publicId() == null || in.publicId().isBlank() ? null : in.publicId();
            ProductImage image;
            if (pid != null && byPublicId.containsKey(pid)) {
                image = byPublicId.get(pid);
            } else if (pid != null) {   // image tmp (vérifié par validate)
                StoredImage moved = storage.move(pid, folder);
                image = new ProductImage(moved.url(), moved.publicId(), moved.width(), moved.height());
            } else if (externalByUrl.containsKey(in.url())) {
                image = externalByUrl.get(in.url());
            } else {
                image = new ProductImage(in.url(), null, null, null);
            }
            if (kept.add(image)) {
                next.add(image);
            }
        }
        List<String> removed = existing.stream()
                .filter(i -> !kept.contains(i))
                .map(ProductImage::getPublicId)
                .filter(Objects::nonNull)
                .toList();
        p.getImages().clear();
        next.forEach(p::addImage);
        return removed;
    }

    /** Slug unique : suffixe -2, -3… en cas de doublon ; produit-{id} si le nom ne donne aucun slug. */
    private String uniqueSlug(String name, String id, String currentSlug) {
        String base = SlugUtil.slugify(name);
        if (base.isEmpty()) {
            base = "produit-" + id;
        }
        String candidate = base;
        for (int i = 2; !candidate.equals(currentSlug) && productRepository.existsBySlug(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private Product load(String id) {
        return productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private void requestDeletion(List<String> publicIds) {
        if (!publicIds.isEmpty()) {
            events.publishEvent(new ImageDeletionRequested(List.copyOf(publicIds)));
        }
    }

    private static List<String> publicIds(List<ProductImage> images) {
        return images.stream().map(ProductImage::getPublicId).filter(Objects::nonNull).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Specification<Product> adminSpec(String search, String category, ProductStatus status) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isNull(root.get("deletedAt")));
            if (search != null && !search.isBlank()) {
                for (String token : SlugUtil.normalize(search).split("\\s+")) {
                    if (!token.isBlank()) {
                        ps.add(cb.like(root.get("searchText"), "%" + token + "%"));
                    }
                }
            }
            if (category != null && !category.isBlank()) {
                ps.add(cb.equal(root.join("category").get("slug"), category.trim()));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
