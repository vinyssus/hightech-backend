package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.dto.catalog.BrandDto;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.BrandRepository;
import cm.hitechdz.auth.repository.catalog.ProductFilter;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ProductSearchRepository;
import cm.hitechdz.auth.service.media.ImageDeletionRequested;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.ImageUploadService;
import cm.hitechdz.auth.service.media.StoredImage;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
public class BrandService {

    static final String NOT_FOUND = "Marque introuvable.";
    static final String ALREADY_EXISTS = "Cette marque existe déjà.";
    static final String NAME_REQUIRED = "Le nom de la marque est obligatoire.";
    static final String EMPTY_SLUG = "Le nom de la marque doit contenir au moins une lettre ou un chiffre.";

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;
    private final ImageUploadService uploadService;
    private final ApplicationEventPublisher events;

    public BrandService(BrandRepository brandRepository, ProductRepository productRepository,
                        ProductSearchRepository searchRepository, ImageUploadService uploadService,
                        ApplicationEventPublisher events) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.searchRepository = searchRepository;
        this.uploadService = uploadService;
        this.events = events;
    }

    /** Toutes les marques avec le nombre de produits publiés qui correspondent aux autres filtres. */
    @Transactional(readOnly = true)
    public List<BrandDto> publicList(ProductFilter filter) {
        Map<String, Long> counts = searchRepository.countByBrand(filter.withoutBrands());
        return brandRepository.findAllOrdered().stream()
                .map(b -> new BrandDto(b.getSlug(), b.getName(), CatalogMapper.deliveryUrl(b.getLogoUrl()),
                        counts.getOrDefault(b.getNameKey(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BrandDto> adminList() {
        return brandRepository.findAllOrdered().stream().map(this::toAdminDto).toList();
    }

    @Transactional
    public BrandDto create(String name, MultipartFile logo) {
        String clean = requireName(name);
        if (brandRepository.findByNameKey(Brand.keyOf(clean)).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_EXISTS, Map.of("name", ALREADY_EXISTS));
        }
        String slug = SlugUtil.slugify(clean);
        if (slug.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, EMPTY_SLUG, Map.of("name", EMPTY_SLUG));
        }
        if (brandRepository.existsBySlug(slug)) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_EXISTS, Map.of("name", ALREADY_EXISTS));
        }
        Brand b = new Brand();
        b.setName(clean);
        b.setSlug(slug);
        if (logo != null && !logo.isEmpty()) {
            StoredImage stored = uploadService.uploadOne(logo, ImageStorageService.BRANDS_FOLDER);
            b.setLogoUrl(stored.url());
            b.setLogoPublicId(stored.publicId());
        }
        brandRepository.saveAndFlush(b);
        return toAdminDto(b);
    }

    /** Renomme la marque (le slug ne change pas) et remplace le logo si un fichier est fourni. */
    @Transactional
    public BrandDto update(String slug, String name, MultipartFile logo) {
        Brand b = load(slug);
        if (name != null && !name.isBlank()) {
            String clean = name.trim();
            brandRepository.findByNameKey(Brand.keyOf(clean))
                    .filter(other -> !other.getId().equals(b.getId()))
                    .ifPresent(other -> {
                        throw new ApiException(HttpStatus.CONFLICT, ALREADY_EXISTS, Map.of("name", ALREADY_EXISTS));
                    });
            b.setName(clean);
            productRepository.findByBrand(b).forEach(SearchText::refresh);
        }
        if (logo != null && !logo.isEmpty()) {
            String oldPublicId = b.getLogoPublicId();
            StoredImage stored = uploadService.uploadOne(logo, ImageStorageService.BRANDS_FOLDER);
            b.setLogoUrl(stored.url());
            b.setLogoPublicId(stored.publicId());
            if (oldPublicId != null) {
                events.publishEvent(new ImageDeletionRequested(List.of(oldPublicId)));
            }
        }
        brandRepository.flush();
        return toAdminDto(b);
    }

    @Transactional
    public void delete(String slug) {
        Brand b = load(slug);
        long used = productRepository.countByBrandAndDeletedAtIsNull(b);
        if (used > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Impossible de supprimer : " + used + " produit(s) utilisent cette marque.");
        }
        if (b.getLogoPublicId() != null) {
            events.publishEvent(new ImageDeletionRequested(List.of(b.getLogoPublicId())));
        }
        brandRepository.delete(b);
    }

    /**
     * Marque d'un produit : retrouvée sans tenir compte de la casse (« hp » = « HP »),
     * sinon créée automatiquement.
     */
    @Transactional
    public Brand resolveOrCreate(String name) {
        String clean = requireName(name);
        return brandRepository.findByNameKey(Brand.keyOf(clean)).orElseGet(() -> {
            Brand b = new Brand();
            b.setName(clean);
            String base = SlugUtil.slugify(clean);
            if (base.isEmpty()) {
                base = "marque";
            }
            String slug = base;
            for (int i = 2; brandRepository.existsBySlug(slug); i++) {
                slug = base + "-" + i;
            }
            b.setSlug(slug);
            return brandRepository.saveAndFlush(b);
        });
    }

    private Brand load(String slug) {
        return brandRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private BrandDto toAdminDto(Brand b) {
        return new BrandDto(b.getSlug(), b.getName(), CatalogMapper.deliveryUrl(b.getLogoUrl()),
                productRepository.countByBrandAndDeletedAtIsNull(b));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NAME_REQUIRED, Map.of("name", NAME_REQUIRED));
        }
        String clean = name.trim();
        if (clean.length() > 60) {
            String msg = "Le nom de la marque ne peut pas dépasser 60 caractères.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("name", msg));
        }
        return clean;
    }
}
