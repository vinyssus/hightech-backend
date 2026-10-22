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
    static final String LOGO_HTTPS = "Le logo doit être une URL https.";
    static final String LOGO_UNKNOWN = "Logo inconnu : envoyez-le d'abord via /api/admin/uploads/images.";

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;
    private final ImageUploadService uploadService;
    private final ImageStorageService storage;
    private final ApplicationEventPublisher events;

    public BrandService(BrandRepository brandRepository, ProductRepository productRepository,
                        ProductSearchRepository searchRepository, ImageUploadService uploadService,
                        ImageStorageService storage, ApplicationEventPublisher events) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.searchRepository = searchRepository;
        this.uploadService = uploadService;
        this.storage = storage;
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
        return create(name, logo, null, null);
    }

    /**
     * Création. Le logo peut arriver en fichier (multipart) ou en URL déjà envoyée
     * (logoUrl + logoPublicId d'une image tmp, déplacée alors dans hitech-dz/brands).
     */
    @Transactional
    public BrandDto create(String name, MultipartFile logo, String logoUrl, String logoPublicId) {
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
        StoredImage stored = resolveLogo(logo, logoUrl, logoPublicId, null);
        if (stored != null) {
            b.setLogoUrl(stored.url());
            b.setLogoPublicId(stored.publicId());
        }
        brandRepository.saveAndFlush(b);
        return toAdminDto(b);
    }

    /** Renomme la marque (le slug ne change pas) et remplace le logo si un fichier est fourni. */
    @Transactional
    public BrandDto update(String slug, String name, MultipartFile logo) {
        return update(slug, name, logo, null, null);
    }

    @Transactional
    public BrandDto update(String slug, String name, MultipartFile logo, String logoUrl, String logoPublicId) {
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
        StoredImage stored = resolveLogo(logo, logoUrl, logoPublicId, b.getLogoPublicId());
        boolean unchanged = stored != null && stored.publicId() != null
                && stored.publicId().equals(b.getLogoPublicId());
        if (stored != null && !unchanged) {
            String oldPublicId = b.getLogoPublicId();
            b.setLogoUrl(stored.url());
            b.setLogoPublicId(stored.publicId());
            if (oldPublicId != null && !oldPublicId.equals(stored.publicId())) {
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

    /**
     * Logo à enregistrer : fichier envoyé, ou URL https (image tmp déplacée dans hitech-dz/brands,
     * logo actuel conservé, ou URL externe). null = aucun changement.
     */
    private StoredImage resolveLogo(MultipartFile file, String url, String publicId, String currentPublicId) {
        if (file != null && !file.isEmpty()) {
            return uploadService.uploadOne(file, ImageStorageService.BRANDS_FOLDER);
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        if (!url.startsWith("https://")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, LOGO_HTTPS, Map.of("logo", LOGO_HTTPS));
        }
        if (publicId == null || publicId.isBlank()) {
            return new StoredImage(url, null, null, null);   // URL externe
        }
        if (publicId.equals(currentPublicId)) {
            return new StoredImage(url, publicId, null, null);   // logo inchangé
        }
        if (!ImageStorageService.isTmp(publicId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, LOGO_UNKNOWN, Map.of("logo", LOGO_UNKNOWN));
        }
        return storage.move(publicId, ImageStorageService.BRANDS_FOLDER);
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
