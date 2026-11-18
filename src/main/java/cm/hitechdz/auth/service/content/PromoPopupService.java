package cm.hitechdz.auth.service.content;

import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.content.DismissMode;
import cm.hitechdz.auth.domain.content.PromoLinkType;
import cm.hitechdz.auth.domain.content.PromoPopupAd;
import cm.hitechdz.auth.domain.content.PromoPopupSettings;
import cm.hitechdz.auth.dto.content.AdminPromoAdDto;
import cm.hitechdz.auth.dto.content.PromoAdData;
import cm.hitechdz.auth.dto.content.PromoPopupResponse;
import cm.hitechdz.auth.dto.content.PromoPopupSpecs;
import cm.hitechdz.auth.dto.content.PromoSettingsDto;
import cm.hitechdz.auth.dto.content.PublicPromoAd;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.CategoryRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.content.PromoPopupAdRepository;
import cm.hitechdz.auth.repository.content.PromoPopupSettingsRepository;
import cm.hitechdz.auth.service.catalog.CatalogMapper;
import cm.hitechdz.auth.service.media.ImageDeletionRequested;
import cm.hitechdz.auth.service.media.ImageInspector;
import cm.hitechdz.auth.service.media.ImageStorageService;
import cm.hitechdz.auth.service.media.StoredImage;
import cm.hitechdz.auth.service.order.OrderAccess;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Panneau publicitaire flottant : publicités verticales 9:16, réglages globaux, mêmes garde-fous que les bannières. */
@Service
public class PromoPopupService {

    public static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    public static final long MAX_GIF_BYTES = 3L * 1024 * 1024;
    public static final long MAX_BYTES = 1024L * 1024;
    public static final int MIN_WIDTH = 360;
    public static final int MIN_HEIGHT = 640;
    public static final int MAX_WIDTH = 1080;
    public static final int MAX_HEIGHT = 1920;
    public static final double RATIO = 9.0 / 16.0;
    public static final double RATIO_TOLERANCE = 0.05;
    public static final int MAX_GIF_FRAMES = 150;
    public static final int MAX_GIF_DURATION_MS = 15_000;
    public static final int MAX_ACTIVE = 6;
    public static final List<String> CATALOGUE_VALUES = List.of("promo", "nouveautes", "popularite");

    static final String NOT_FOUND = "Publicité introuvable.";
    static final String VALIDATION = "Validation échouée";
    static final String LIMIT = "Nombre maximum de publicités actives atteint (6).";

    private final PromoPopupAdRepository ads;
    private final PromoPopupSettingsRepository settingsRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ImageStorageService storage;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Validator validator;

    public PromoPopupService(PromoPopupAdRepository ads, PromoPopupSettingsRepository settingsRepository,
                             CategoryRepository categoryRepository, ProductRepository productRepository,
                             ImageStorageService storage, ApplicationEventPublisher events, ObjectMapper json,
                             Validator validator) {
        this.ads = ads;
        this.settingsRepository = settingsRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.storage = storage;
        this.events = events;
        this.json = json;
        this.validator = validator;
    }

    private record CheckedImage(byte[] bytes, ImageInspector.Info info, ImageInspector.GifAnimation animation) {}

    private record Checked(PromoAdData data, PromoLinkType linkType, String linkValue, Instant startsAt, Instant endsAt) {}

    // ------------------------------------------------------------------ public
    @Transactional(readOnly = true)
    public PromoPopupResponse publicView() {
        PromoPopupSettings s = settings();
        List<PublicPromoAd> list = !s.isEnabled() ? List.of() : ads.findVisible(Instant.now()).stream()
                .map(a -> new PublicPromoAd(a.getId(), CatalogMapper.deliveryUrl(a.getImageUrl()),
                        CatalogMapper.deliveryUrl(a.getMobileImageUrl()), a.getAltText(), a.getLinkType().name(),
                        a.getLinkValue(), a.getPosition()))
                .toList();
        return new PromoPopupResponse(toDto(s), list, s.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    public PromoPopupSpecs specs() {
        return new PromoPopupSpecs(List.of("image/gif", "image/webp", "image/png", "image/jpeg"), MAX_GIF_BYTES, MAX_BYTES,
                "9:16", RATIO_TOLERANCE, MIN_WIDTH, MIN_HEIGHT, MAX_WIDTH, MAX_HEIGHT, "720x1280",
                MAX_GIF_FRAMES, MAX_GIF_DURATION_MS, MAX_ACTIVE,
                new PromoPopupSpecs.Range(500, 10000, 1500), new PromoPopupSpecs.Range(2000, 15000, 4000),
                CATALOGUE_VALUES, Arrays.stream(DismissMode.values()).map(Enum::name).toList(), DOUALA.getId());
    }

    // ------------------------------------------------------------------ réglages
    @Transactional(readOnly = true)
    public PromoSettingsDto adminSettings() {
        return toDto(settings());
    }

    @Transactional
    public PromoSettingsDto updateSettings(PromoSettingsDto req) {
        DismissMode mode;
        try {
            mode = DismissMode.valueOf(req.dismissMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION,
                    Map.of("dismissMode", "Mode de fermeture invalide (SESSION, DAY ou NEVER_AGAIN)."));
        }
        PromoPopupSettings s = settings();
        s.setEnabled(req.enabled());
        s.setDelayMs(req.delayMs());
        s.setIntervalMs(req.intervalMs());
        s.setShowOnMobile(req.showOnMobile());
        s.setDismissMode(mode);
        s.touch();
        return toDto(settingsRepository.saveAndFlush(s));
    }

    // ------------------------------------------------------------------ publicités : lecture
    @Transactional(readOnly = true)
    public List<AdminPromoAdDto> adminList() {
        Instant now = Instant.now();
        return ads.findAllOrdered().stream().map(a -> toAdmin(a, now)).toList();
    }

    @Transactional(readOnly = true)
    public AdminPromoAdDto get(UUID id) {
        return toAdmin(load(id), Instant.now());
    }

    // ------------------------------------------------------------------ publicités : écriture
    @Transactional
    public AdminPromoAdDto create(String data, MultipartFile image, MultipartFile mobileImage) {
        Checked c = checkData(data);
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of("image", "L'image est obligatoire."));
        }
        CheckedImage main = checkImage(image, "image");
        CheckedImage mobile = present(mobileImage) ? checkImage(mobileImage, "mobileImage") : null;

        List<PromoPopupAd> all = ads.lockAll();
        PromoPopupAd a = new PromoPopupAd();
        apply(a, c);
        a.setActive(c.data().active() == null || c.data().active());
        a.setPosition(c.data().position() != null ? c.data().position()
                : all.stream().mapToInt(PromoPopupAd::getPosition).max().orElse(-1) + 1);
        a.setCreatedBy(OrderAccess.currentUserId());
        checkLimit(a, all);

        setMainImage(a, main, upload(main));
        if (mobile != null) {
            setMobileImage(a, upload(mobile));
        }
        PromoPopupAd saved = ads.saveAndFlush(a);
        touchSettings();
        return toAdmin(saved, Instant.now());
    }

    @Transactional
    public AdminPromoAdDto update(UUID id, String data, MultipartFile image, MultipartFile mobileImage) {
        PromoPopupAd a = load(id);
        Checked c = checkData(data);
        CheckedImage main = present(image) ? checkImage(image, "image") : null;
        CheckedImage mobile = present(mobileImage) ? checkImage(mobileImage, "mobileImage") : null;

        List<PromoPopupAd> all = ads.lockAll();
        apply(a, c);
        if (c.data().active() != null) {
            a.setActive(c.data().active());
        }
        if (c.data().position() != null) {
            a.setPosition(c.data().position());
        }
        checkLimit(a, all);

        List<String> toDelete = new ArrayList<>();
        if (main != null) {
            toDelete.add(a.getImagePublicId());
            setMainImage(a, main, upload(main));
        }
        if (mobile != null) {
            toDelete.add(a.getMobileImagePublicId());
            setMobileImage(a, upload(mobile));
        } else if (Boolean.TRUE.equals(c.data().removeMobileImage())) {
            toDelete.add(a.getMobileImagePublicId());
            a.setMobileImageUrl(null);
            a.setMobileImagePublicId(null);
        }
        ads.saveAndFlush(a);
        touchSettings();
        deleteAfterCommit(toDelete);
        return toAdmin(a, Instant.now());
    }

    @Transactional
    public AdminPromoAdDto setActive(UUID id, boolean active) {
        PromoPopupAd a = load(id);
        List<PromoPopupAd> all = ads.lockAll();
        a.setActive(active);
        checkLimit(a, all);
        ads.saveAndFlush(a);
        touchSettings();
        return toAdmin(a, Instant.now());
    }

    @Transactional
    public List<AdminPromoAdDto> reorder(List<UUID> ids) {
        List<PromoPopupAd> all = ads.lockAll();
        Set<UUID> expected = new HashSet<>();
        all.forEach(a -> expected.add(a.getId()));
        if (ids.size() != expected.size() || !expected.equals(new HashSet<>(ids))) {
            String msg = "Envoyez chaque publicité exactement une fois.";
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of("ids", msg));
        }
        Map<UUID, PromoPopupAd> byId = new LinkedHashMap<>();
        all.forEach(a -> byId.put(a.getId(), a));
        Instant now = Instant.now();
        List<AdminPromoAdDto> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            PromoPopupAd a = byId.get(ids.get(i));
            a.setPosition(i);
            out.add(toAdmin(a, now));
        }
        ads.flush();
        touchSettings();
        return out;
    }

    @Transactional
    public void delete(UUID id) {
        PromoPopupAd a = load(id);
        ads.delete(a);
        touchSettings();
        deleteAfterCommit(Arrays.asList(a.getImagePublicId(), a.getMobileImagePublicId()));
    }

    // ------------------------------------------------------------------ validation des données
    private Checked checkData(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of("data", "Les données de la publicité sont obligatoires."));
        }
        PromoAdData d;
        try {
            d = json.readValue(raw, PromoAdData.class);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of("data", "Données invalides : JSON attendu."));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<PromoAdData> v : validator.validate(d)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        PromoLinkType linkType = PromoLinkType.NONE;
        if (d.linkType() != null && !d.linkType().isBlank()) {
            try {
                linkType = PromoLinkType.valueOf(d.linkType().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("linkType", "Type de lien invalide (NONE, CATEGORY, SUBCATEGORY, PRODUCT, CATALOGUE ou EXTERNAL).");
            }
        }
        String linkValue = d.linkValue() == null || d.linkValue().isBlank() ? null : d.linkValue().trim();
        if (!errors.containsKey("linkType") && !errors.containsKey("linkValue")) {
            String linkError = linkError(linkType, linkValue);
            if (linkError != null) {
                errors.put("linkValue", linkError);
            }
        }
        Instant startsAt = parseDate(d.startsAt(), "startsAt", errors);
        Instant endsAt = parseDate(d.endsAt(), "endsAt", errors);
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            errors.put("endsAt", "La date de fin doit être postérieure à la date de début.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, errors);
        }
        return new Checked(d, linkType, linkValue, startsAt, endsAt);
    }

    /** « 2026-12-01T08:00 » = heure de Douala ; une date avec fuseau (Z, +01:00) est prise telle quelle. Stockée en UTC. */
    static Instant parseDate(String value, String field, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // pas de fuseau : heure de Douala
        }
        try {
            return LocalDateTime.parse(v).atZone(DOUALA).toInstant();
        } catch (DateTimeParseException e) {
            errors.put(field, "Date invalide : format attendu 2026-12-01T08:00 (heure de Douala).");
            return null;
        }
    }

    private String linkError(PromoLinkType type, String value) {
        if (type == PromoLinkType.NONE) {
            return value == null ? null : "Aucun lien attendu quand le type est NONE.";
        }
        if (value == null) {
            return "Le lien est obligatoire pour ce type de lien.";
        }
        return switch (type) {
            case CATEGORY -> categoryRepository.existsBySlug(value) ? null : "Catégorie introuvable.";
            case SUBCATEGORY -> {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    yield "Format attendu : categorie/sous-categorie (ex. ordinateurs/gaming).";
                }
                Category c = categoryRepository.findBySlug(parts[0]).orElse(null);
                yield c != null && c.findSubcategory(parts[1]).isPresent() ? null : "Sous-catégorie introuvable.";
            }
            case PRODUCT -> productRepository.findBySlugAndDeletedAtIsNull(value).filter(p -> p.isPublic()).isPresent()
                    ? null : "Produit introuvable ou non publié.";
            case CATALOGUE -> CATALOGUE_VALUES.contains(value) ? null
                    : "Filtre catalogue invalide (promo, nouveautes ou popularite).";
            case EXTERNAL -> HomeBannerService.validHttpsUrl(value) ? null
                    : "Le lien doit être une URL https:// complète (ex. https://exemple.cm/page).";
            case NONE -> null;
        };
    }

    // ------------------------------------------------------------------ validation des images
    private CheckedImage checkImage(MultipartFile file, String field) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of(field, "Fichier illisible."));
        }
        ImageInspector.Result r = ImageInspector.inspect(bytes);
        if (!r.accepted()) {
            String msg = r.rejectedFormat() != null
                    ? "Format " + r.rejectedFormat() + " refusé : utilisez GIF, WebP, PNG ou JPEG."
                    : "Fichier non reconnu comme image : utilisez GIF, WebP, PNG ou JPEG.";
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format d'image refusé", Map.of(field, msg));
        }
        ImageInspector.Info info = r.info();
        boolean gif = info.format() == ImageInspector.Format.GIF;
        if (gif && bytes.length > MAX_GIF_BYTES) {
            throw invalid(field, "Le GIF dépasse 3 Mo (reçu : " + megabytes(bytes.length) + ").");
        }
        if (!gif && bytes.length > MAX_BYTES) {
            throw invalid(field, "L'image dépasse 1 Mo (reçue : " + megabytes(bytes.length) + "). Seuls les GIF peuvent aller jusqu'à 3 Mo.");
        }
        String received = " Reçue : " + info.width() + "×" + info.height() + " px.";
        // Ratio d'abord : pour une image horizontale, c'est l'erreur la plus utile
        double ratio = (double) info.width() / info.height();
        if (Math.abs(ratio - RATIO) / RATIO > RATIO_TOLERANCE) {
            throw invalid(field, "L'image doit être au format vertical 9:16 (ex. 720×1280)." + received);
        }
        if (info.width() > MAX_WIDTH || info.height() > MAX_HEIGHT) {
            throw invalid(field, "L'image ne doit pas dépasser " + MAX_WIDTH + "×" + MAX_HEIGHT + " px." + received);
        }
        if (info.width() < MIN_WIDTH || info.height() < MIN_HEIGHT) {
            throw invalid(field, "L'image doit faire au moins " + MIN_WIDTH + "×" + MIN_HEIGHT + " px." + received);
        }
        ImageInspector.GifAnimation animation = null;
        if (gif) {
            animation = ImageInspector.gifAnimation(bytes).orElseThrow(() -> invalid(field, "GIF illisible ou corrompu."));
            if (animation.frames() > MAX_GIF_FRAMES) {
                throw invalid(field, "Le GIF contient trop d'images (" + animation.frames() + ", maximum " + MAX_GIF_FRAMES + ").");
            }
            if (animation.durationMs() > MAX_GIF_DURATION_MS) {
                throw invalid(field, "Le GIF dure trop longtemps (" + String.format(Locale.FRANCE, "%.1f", animation.durationMs() / 1000.0)
                        + " s, maximum 15 s).");
            }
        }
        return new CheckedImage(bytes, info, animation);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, Map.of(field, message));
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ helpers
    private void checkLimit(PromoPopupAd target, List<PromoPopupAd> all) {
        Instant now = Instant.now();
        if (!counts(target, now)) {
            return;
        }
        long others = all.stream()
                .filter(a -> target.getId() == null || !a.getId().equals(target.getId()))
                .filter(a -> counts(a, now))
                .count();
        if (others >= MAX_ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, LIMIT);
        }
    }

    /** Compte dans la limite : active et non expirée (en ligne ou programmée). */
    private static boolean counts(PromoPopupAd a, Instant now) {
        PromoPopupAd.Status s = a.statusAt(now);
        return s == PromoPopupAd.Status.LIVE || s == PromoPopupAd.Status.SCHEDULED;
    }

    private static void apply(PromoPopupAd a, Checked c) {
        a.setAltText(c.data().altText().trim());
        a.setLinkType(c.linkType());
        a.setLinkValue(c.linkValue());
        a.setStartsAt(c.startsAt());
        a.setEndsAt(c.endsAt());
    }

    private StoredImage upload(CheckedImage image) {
        StoredImage stored = storage.uploadBanner(image.bytes(), ImageStorageService.PROMO_POPUP_FOLDER);
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

    private static void setMainImage(PromoPopupAd a, CheckedImage image, StoredImage stored) {
        a.setImageUrl(stored.url());
        a.setImagePublicId(stored.publicId());
        a.setWidth(image.info().width());
        a.setHeight(image.info().height());
        a.setSizeBytes(image.bytes().length);
        a.setFormat(image.info().format().key());
        a.setFrames(image.animation() == null ? null : image.animation().frames());
        a.setDurationMs(image.animation() == null ? null : image.animation().durationMs());
    }

    private static void setMobileImage(PromoPopupAd a, StoredImage stored) {
        a.setMobileImageUrl(stored.url());
        a.setMobileImagePublicId(stored.publicId());
    }

    private void touchSettings() {
        PromoPopupSettings s = settings();
        s.touch();
        settingsRepository.save(s);
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

    private PromoPopupSettings settings() {
        return settingsRepository.findById(PromoPopupSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Réglages du panneau publicitaire absents (migration V9)."));
    }

    private PromoPopupAd load(UUID id) {
        return ads.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static PromoSettingsDto toDto(PromoPopupSettings s) {
        return new PromoSettingsDto(s.isEnabled(), s.getDelayMs(), s.getIntervalMs(), s.isShowOnMobile(), s.getDismissMode().name());
    }

    private static String local(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, DOUALA).truncatedTo(ChronoUnit.MINUTES).toString();
    }

    private static AdminPromoAdDto toAdmin(PromoPopupAd a, Instant now) {
        return new AdminPromoAdDto(a.getId(), CatalogMapper.deliveryUrl(a.getImageUrl()), a.getImagePublicId(),
                CatalogMapper.deliveryUrl(a.getMobileImageUrl()), a.getMobileImagePublicId(), a.getAltText(),
                a.getLinkType().name(), a.getLinkValue(), a.getPosition(), a.isActive(), a.getStartsAt(), a.getEndsAt(),
                local(a.getStartsAt()), local(a.getEndsAt()), a.statusAt(now).name(), a.getWidth(), a.getHeight(),
                a.getSizeBytes(), a.getFormat(), a.getFrames(), a.getDurationMs(), a.getCreatedBy(), a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
