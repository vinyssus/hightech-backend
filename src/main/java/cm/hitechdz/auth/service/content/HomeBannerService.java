package cm.hitechdz.auth.service.content;

import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.content.BannerLinkType;
import cm.hitechdz.auth.domain.content.BannerSlot;
import cm.hitechdz.auth.domain.content.HomeBanner;
import cm.hitechdz.auth.dto.content.AdminBannerDto;
import cm.hitechdz.auth.dto.content.BannerData;
import cm.hitechdz.auth.dto.content.BannerSpecs;
import cm.hitechdz.auth.dto.content.PublicBannerDto;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.CategoryRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.content.HomeBannerRepository;
import cm.hitechdz.auth.service.catalog.CatalogMapper;
import cm.hitechdz.auth.service.media.ImageDeletionRequested;
import cm.hitechdz.auth.service.media.ImageInspector;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.StoredImage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bannières d'accueil : validation stricte des images, liens vérifiés, limite par emplacement. */
@Service
public class HomeBannerService {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_GIF_BYTES = 4L * 1024 * 1024;
    public static final int MAX_PER_SLOT = 6;
    public static final int MAX_DIMENSION = 4000;
    public static final int MOBILE_MIN_WIDTH = 750;
    public static final double RATIO_TOLERANCE = 0.05;

    static final String NOT_FOUND = "Affiche introuvable.";
    static final String INVALID = "Données invalides.";
    static final String INVALID_IMAGE = "Image invalide";
    static final String LIMIT = "Nombre maximum d'affiches atteint pour cet emplacement";

    private final HomeBannerRepository repository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ImageStorageService storage;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Validator validator;

    public HomeBannerService(HomeBannerRepository repository, CategoryRepository categoryRepository,
                             ProductRepository productRepository, ImageStorageService storage,
                             ApplicationEventPublisher events, ObjectMapper json, Validator validator) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.storage = storage;
        this.events = events;
        this.json = json;
        this.validator = validator;
    }

    /** Image validée, prête à être envoyée. */
    private record CheckedImage(byte[] bytes, ImageInspector.Info info) {}

    /** Données du formulaire validées. */
    private record Checked(BannerData data, BannerSlot slot, BannerLinkType linkType, String linkValue) {}

    // ------------------------------------------------------------------ public
    @Transactional(readOnly = true)
    public Map<String, List<PublicBannerDto>> publicHome() {
        Map<String, List<PublicBannerDto>> out = new LinkedHashMap<>();
        for (BannerSlot s : BannerSlot.values()) {
            out.put(s.name(), new ArrayList<>());
        }
        for (HomeBanner b : repository.findVisible(Instant.now())) {
            out.get(b.getSlot().name()).add(new PublicBannerDto(b.getId(), CatalogMapper.deliveryUrl(b.getImageUrl()),
                    CatalogMapper.deliveryUrl(b.getMobileImageUrl()), b.getAltText(), b.getLinkType().name(),
                    b.getLinkValue(), b.getPosition()));
        }
        return out;
    }

    // ------------------------------------------------------------------ admin : lecture
    @Transactional(readOnly = true)
    public List<AdminBannerDto> adminList(String slot, Boolean active) {
        BannerSlot filter = slot == null || slot.isBlank() ? null : parseSlot(slot, "slot");
        Instant now = Instant.now();
        return repository.findAllOrdered().stream()
                .filter(b -> filter == null || b.getSlot() == filter)
                .filter(b -> active == null || b.isActive() == active)
                .map(b -> toAdmin(b, now)).toList();
    }

    @Transactional(readOnly = true)
    public AdminBannerDto get(Long id) {
        return toAdmin(load(id), Instant.now());
    }

    public BannerSpecs specs() {
        Map<String, BannerSpecs.SlotSpec> slots = new LinkedHashMap<>();
        for (BannerSlot s : BannerSlot.values()) {
            slots.put(s.name(), new BannerSpecs.SlotSpec(s.ratioLabel(), s.minWidth(), s.minHeight(), s.recommended()));
        }
        return new BannerSpecs(List.of("image/jpeg", "image/png", "image/webp", "image/gif"), MAX_BYTES, MAX_GIF_BYTES,
                MAX_PER_SLOT, MAX_DIMENSION, MAX_DIMENSION, MOBILE_MIN_WIDTH, RATIO_TOLERANCE, slots);
    }

    // ------------------------------------------------------------------ admin : écriture
    @Transactional
    public AdminBannerDto create(String data, MultipartFile image, MultipartFile mobileImage) {
        Checked c = checkData(data);
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of("image", "L'image est obligatoire."));
        }
        CheckedImage main = checkImage(image, "image", c.slot(), false);
        CheckedImage mobile = present(mobileImage) ? checkImage(mobileImage, "mobileImage", c.slot(), true) : null;

        List<HomeBanner> slotBanners = repository.lockSlot(c.slot());
        HomeBanner b = new HomeBanner();
        apply(b, c);
        b.setActive(c.data().active() == null || c.data().active());
        b.setPosition(c.data().position() != null ? c.data().position() : nextPosition(slotBanners));
        checkLimit(b, slotBanners);

        setMainImage(b, main, upload(main, c.slot()));
        if (mobile != null) {
            setMobileImage(b, mobile, upload(mobile, c.slot()));
        }
        return toAdmin(repository.saveAndFlush(b), Instant.now());
    }

    @Transactional
    public AdminBannerDto update(Long id, String data, MultipartFile image, MultipartFile mobileImage) {
        HomeBanner b = load(id);
        Checked c = checkData(data);
        CheckedImage main = present(image) ? checkImage(image, "image", c.slot(), false) : null;
        CheckedImage mobile = present(mobileImage) ? checkImage(mobileImage, "mobileImage", c.slot(), true) : null;
        if (main == null && c.slot() != b.getSlot()) {
            // Changement d'emplacement sans nouvelle image : l'image actuelle doit respecter les normes du nouvel emplacement
            String error = dimensionError(c.slot(), b.getWidth(), b.getHeight());
            if (error != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE,
                        Map.of("image", error + " Envoyez une nouvelle image pour cet emplacement."));
            }
        }

        BannerSlot oldSlot = b.getSlot();
        List<HomeBanner> slotBanners = repository.lockSlot(c.slot());
        apply(b, c);
        if (c.data().active() != null) {
            b.setActive(c.data().active());
        }
        if (c.data().position() != null) {
            b.setPosition(c.data().position());
        } else if (oldSlot != c.slot()) {
            b.setPosition(nextPosition(slotBanners));
        }
        checkLimit(b, slotBanners);

        List<String> toDelete = new ArrayList<>();
        if (main != null) {
            toDelete.add(b.getImagePublicId());
            setMainImage(b, main, upload(main, c.slot()));
        }
        if (mobile != null) {
            toDelete.add(b.getMobileImagePublicId());
            setMobileImage(b, mobile, upload(mobile, c.slot()));
        } else if (Boolean.TRUE.equals(c.data().removeMobileImage())) {
            toDelete.add(b.getMobileImagePublicId());
            b.setMobileImageUrl(null);
            b.setMobileImagePublicId(null);
            b.setMobileWidth(null);
            b.setMobileHeight(null);
        }
        repository.saveAndFlush(b);
        deleteAfterCommit(toDelete);
        return toAdmin(b, Instant.now());
    }

    @Transactional
    public AdminBannerDto setActive(Long id, boolean active) {
        HomeBanner b = load(id);
        List<HomeBanner> slotBanners = repository.lockSlot(b.getSlot());
        b.setActive(active);
        checkLimit(b, slotBanners);
        return toAdmin(repository.saveAndFlush(b), Instant.now());
    }

    /** Réécrit les positions de tout l'emplacement en une transaction. */
    @Transactional
    public List<AdminBannerDto> reorder(String slotValue, List<Long> ids) {
        BannerSlot slot = parseSlot(slotValue, "slot");
        List<HomeBanner> banners = repository.lockSlot(slot);
        Set<Long> expected = new HashSet<>();
        banners.forEach(b -> expected.add(b.getId()));
        if (ids.size() != expected.size() || !expected.equals(new HashSet<>(ids))) {
            String msg = "Envoyez chaque affiche de l'emplacement exactement une fois.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("ids", msg));
        }
        Map<Long, HomeBanner> byId = new LinkedHashMap<>();
        banners.forEach(b -> byId.put(b.getId(), b));
        List<AdminBannerDto> out = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < ids.size(); i++) {
            HomeBanner b = byId.get(ids.get(i));
            b.setPosition(i);
            out.add(toAdmin(b, now));
        }
        repository.flush();
        return out;
    }

    @Transactional
    public void delete(Long id) {
        HomeBanner b = load(id);
        repository.delete(b);
        deleteAfterCommit(List.of(nullToEmpty(b.getImagePublicId()), nullToEmpty(b.getMobileImagePublicId())));
    }

    // ------------------------------------------------------------------ validation des données
    private Checked checkData(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID, Map.of("data", "Les données de l'affiche sont obligatoires."));
        }
        BannerData d;
        try {
            d = json.readValue(raw, BannerData.class);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID,
                    Map.of("data", "Données invalides : JSON attendu (dates au format ISO 8601, ex. 2026-12-01T00:00:00Z)."));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<BannerData> v : validator.validate(d)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        BannerSlot slot = null;
        if (d.slot() != null && !d.slot().isBlank()) {
            try {
                slot = BannerSlot.valueOf(d.slot().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("slot", "Emplacement invalide (MAIN, SIDE_TOP ou SIDE_BOTTOM).");
            }
        }
        BannerLinkType linkType = BannerLinkType.NONE;
        if (d.linkType() != null && !d.linkType().isBlank()) {
            try {
                linkType = BannerLinkType.valueOf(d.linkType().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("linkType", "Type de lien invalide (CATEGORY, SUBCATEGORY, PRODUCT, URL ou NONE).");
            }
        }
        String linkValue = d.linkValue() == null || d.linkValue().isBlank() ? null : d.linkValue().trim();
        if (!errors.containsKey("linkType") && !errors.containsKey("linkValue")) {
            String linkError = linkError(linkType, linkValue);
            if (linkError != null) {
                errors.put("linkValue", linkError);
            }
        }
        if (d.startsAt() != null && d.endsAt() != null && !d.endsAt().isAfter(d.startsAt())) {
            errors.put("endsAt", "La date de fin doit être postérieure à la date de début.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID, errors);
        }
        return new Checked(d, slot, linkType, linkType == BannerLinkType.NONE ? null : linkValue);
    }

    private String linkError(BannerLinkType type, String value) {
        if (type == BannerLinkType.NONE) {
            return null;
        }
        if (value == null) {
            return "Le lien est obligatoire pour ce type de lien.";
        }
        return switch (type) {
            case CATEGORY -> categoryRepository.existsBySlug(value) ? null : "Cette catégorie n'existe pas.";
            case SUBCATEGORY -> {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    yield "Format attendu : categorie/sous-categorie (ex. ordinateurs/gaming).";
                }
                Category c = categoryRepository.findBySlug(parts[0]).orElse(null);
                yield c != null && c.findSubcategory(parts[1]).isPresent() ? null
                        : "Cette sous-catégorie n'existe pas dans cette catégorie.";
            }
            case PRODUCT -> productRepository.findBySlugAndDeletedAtIsNull(value).filter(p -> p.isPublic()).isPresent()
                    ? null : "Ce produit n'existe pas ou n'est pas publié.";
            case URL -> validHttpsUrl(value) ? null : "Le lien doit être une URL https complète (ex. https://exemple.cm/page).";
            case NONE -> null;
        };
    }

    static boolean validHttpsUrl(String value) {
        if (value.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ validation des images
    private CheckedImage checkImage(MultipartFile file, String field, BannerSlot slot, boolean mobile) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of(field, "Fichier illisible."));
        }
        ImageInspector.Result r = ImageInspector.inspect(bytes);
        if (!r.accepted()) {
            String msg = r.rejectedFormat() != null
                    ? "Format " + r.rejectedFormat() + " refusé : utilisez JPEG, PNG, WebP ou GIF."
                    : "Fichier non reconnu comme image : utilisez JPEG, PNG, WebP ou GIF.";
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format d'image refusé", Map.of(field, msg));
        }
        ImageInspector.Info info = r.info();
        long max = info.format() == ImageInspector.Format.GIF ? MAX_GIF_BYTES : MAX_BYTES;
        if (bytes.length > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of(field,
                    "L'image dépasse " + (max / (1024 * 1024)) + " Mo" + (info.format() == ImageInspector.Format.GIF ? " (GIF)" : "")
                            + ". Reçue : " + megabytes(bytes.length) + "."));
        }
        String received = " Reçue : " + info.width() + "×" + info.height() + " px.";
        if (info.width() > MAX_DIMENSION || info.height() > MAX_DIMENSION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of(field,
                    "L'image ne doit pas dépasser " + MAX_DIMENSION + "×" + MAX_DIMENSION + " px." + received));
        }
        if (mobile) {
            if (info.width() < MOBILE_MIN_WIDTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of(field,
                        "La version mobile doit faire au moins " + MOBILE_MIN_WIDTH + " px de large." + received));
            }
        } else {
            String error = dimensionError(slot, info.width(), info.height());
            if (error != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, Map.of(field, error));
            }
        }
        return new CheckedImage(bytes, info);
    }

    /** Contrôle dimensions minimales et ratio d'un emplacement ; null si conforme. */
    static String dimensionError(BannerSlot slot, int width, int height) {
        String received = " Reçue : " + width + "×" + height + " px.";
        if (width < slot.minWidth() || height < slot.minHeight()) {
            return "L'image doit faire au moins " + slot.minWidth() + "×" + slot.minHeight() + " px." + received;
        }
        double ratio = (double) width / height;
        if (Math.abs(ratio - slot.ratio()) / slot.ratio() > RATIO_TOLERANCE) {
            return "L'image doit être au format " + slot.ratioLabel() + " (ex. " + slot.recommended().replace('x', '×')
                    + " px)." + received;
        }
        return null;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ helpers
    private void checkLimit(HomeBanner target, List<HomeBanner> slotBanners) {
        Instant now = Instant.now();
        if (!counts(target, now)) {
            return;
        }
        long others = slotBanners.stream()
                .filter(b -> target.getId() == null || !b.getId().equals(target.getId()))
                .filter(b -> counts(b, now))
                .count();
        if (others >= MAX_PER_SLOT) {
            throw new ApiException(HttpStatus.CONFLICT, LIMIT);
        }
    }

    /** Compte dans la limite : active et non expirée (les affiches programmées comptent). */
    private static boolean counts(HomeBanner b, Instant now) {
        HomeBanner.Status s = b.statusAt(now);
        return s == HomeBanner.Status.ACTIVE || s == HomeBanner.Status.SCHEDULED;
    }

    private static int nextPosition(List<HomeBanner> slotBanners) {
        return slotBanners.stream().mapToInt(HomeBanner::getPosition).max().orElse(-1) + 1;
    }

    private static void apply(HomeBanner b, Checked c) {
        b.setSlot(c.slot());
        b.setAltText(c.data().altText().trim());
        b.setLinkType(c.linkType());
        b.setLinkValue(c.linkValue());
        b.setStartsAt(c.data().startsAt());
        b.setEndsAt(c.data().endsAt());
    }

    /** Envoie sur Cloudinary ; si la transaction échoue ensuite, l'image envoyée est supprimée. */
    private StoredImage upload(CheckedImage image, BannerSlot slot) {
        StoredImage stored = storage.uploadBanner(image.bytes(), ImageStorageService.BANNERS_FOLDER + "/" + slot.folderName());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storage.delete(stored.publicId());
                    }
                }
            });
        }
        return stored;
    }

    private static void setMainImage(HomeBanner b, CheckedImage image, StoredImage stored) {
        b.setImageUrl(stored.url());
        b.setImagePublicId(stored.publicId());
        b.setWidth(image.info().width());
        b.setHeight(image.info().height());
        b.setSizeBytes(image.bytes().length);
        b.setFormat(image.info().format().key());
    }

    private static void setMobileImage(HomeBanner b, CheckedImage image, StoredImage stored) {
        b.setMobileImageUrl(stored.url());
        b.setMobileImagePublicId(stored.publicId());
        b.setMobileWidth(image.info().width());
        b.setMobileHeight(image.info().height());
    }

    private void deleteAfterCommit(List<String> publicIds) {
        List<String> ids = publicIds.stream().filter(p -> p != null && !p.isBlank()).toList();
        if (!ids.isEmpty()) {
            events.publishEvent(new ImageDeletionRequested(ids));
        }
    }

    private static boolean present(MultipartFile f) {
        return f != null && !f.isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static BannerSlot parseSlot(String value, String field) {
        try {
            return BannerSlot.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String msg = "Emplacement invalide (MAIN, SIDE_TOP ou SIDE_BOTTOM).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
        }
    }

    private HomeBanner load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static AdminBannerDto toAdmin(HomeBanner b, Instant now) {
        return new AdminBannerDto(b.getId(), b.getSlot().name(), CatalogMapper.deliveryUrl(b.getImageUrl()),
                b.getImagePublicId(), CatalogMapper.deliveryUrl(b.getMobileImageUrl()), b.getMobileImagePublicId(),
                b.getAltText(), b.getLinkType().name(), b.getLinkValue(), b.getPosition(), b.isActive(),
                b.getStartsAt(), b.getEndsAt(), b.statusAt(now).name(), b.getWidth(), b.getHeight(), b.getSizeBytes(),
                b.getFormat(), b.getMobileWidth(), b.getMobileHeight(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
