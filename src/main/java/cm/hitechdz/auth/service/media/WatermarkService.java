package cm.hitechdz.auth.service.media;

import cm.hitechdz.auth.domain.content.WatermarkSettings;
import cm.hitechdz.auth.dto.content.WatermarkSettingsDto;
import cm.hitechdz.auth.dto.content.WatermarkStatusDto;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.content.WatermarkSettingsRepository;
import cm.hitechdz.auth.service.catalog.CatalogMapper;
import cm.hitechdz.auth.util.Tokens;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logo HITECH DZ incrusté automatiquement sur les nouvelles photos produits.
 * Le logo par défaut est intégré à l'application et envoyé sur Cloudinary au premier usage ;
 * l'administrateur peut le remplacer, régler position/taille/marge/opacité ou désactiver la fonction.
 */
@Service
public class WatermarkService {

    public static final String DEFAULT_LOGO_RESOURCE = "branding/hitech-dz-logo.png";
    public static final String DEFAULT_LOGO_PUBLIC_ID = ImageStorageService.BRAND_FOLDER + "/watermark-default";
    public static final List<String> POSITIONS = List.of("SOUTH_EAST", "SOUTH_WEST", "SOUTH", "NORTH_EAST", "NORTH_WEST", "CENTER");
    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    private final WatermarkSettingsRepository repository;
    private final ImageStorageService storage;
    private final ApplicationEventPublisher events;

    public WatermarkService(WatermarkSettingsRepository repository, ImageStorageService storage,
                            ApplicationEventPublisher events) {
        this.repository = repository;
        this.storage = storage;
        this.events = events;
    }

    /**
     * Logo à appliquer aux prochaines photos produits ; null si la fonction est désactivée.
     * Envoie le logo par défaut sur Cloudinary s'il n'y est pas encore (une seule fois, sous verrou).
     */
    @Transactional
    public Watermark current() {
        WatermarkSettings s = repository.findById(WatermarkSettings.SINGLETON_ID).orElseThrow();
        if (!s.isEnabled()) {
            return null;
        }
        if (s.getLogoPublicId() == null) {
            s = repository.lockSingleton().orElseThrow();
            if (s.getLogoPublicId() == null) {
                StoredImage logo = storage.uploadBrandAsset(defaultLogo(), DEFAULT_LOGO_PUBLIC_ID);
                s.setLogoPublicId(logo.publicId());
                s.setLogoUrl(logo.url());
                s.setCustomLogo(false);
                s.touch();
                repository.saveAndFlush(s);
            }
        }
        return toWatermark(s);
    }

    @Transactional(readOnly = true)
    public WatermarkStatusDto status() {
        return toStatus(settings());
    }

    @Transactional
    public WatermarkStatusDto update(WatermarkSettingsDto req) {
        String position = req.position().trim().toUpperCase(Locale.ROOT);
        if (!POSITIONS.contains(position)) {
            String msg = "Position invalide (SOUTH_EAST, SOUTH_WEST, SOUTH, NORTH_EAST, NORTH_WEST ou CENTER).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("position", msg));
        }
        WatermarkSettings s = settings();
        s.setEnabled(req.enabled());
        s.setPosition(position);
        s.setWidthPercent(req.widthPercent());
        s.setMarginPercent(req.marginPercent());
        s.setOpacity(req.opacity());
        s.touch();
        return toStatus(repository.saveAndFlush(s));
    }

    /** Nouveau logo (PNG ou WebP, transparent de préférence) ; l'ancien logo personnalisé est supprimé. */
    @Transactional
    public WatermarkStatusDto replaceLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("Choisissez un fichier PNG.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw invalid("Fichier illisible.");
        }
        ImageInspector.Result r = ImageInspector.inspect(bytes);
        if (!r.accepted() || (r.info().format() != ImageInspector.Format.PNG && r.info().format() != ImageInspector.Format.WEBP)) {
            String msg = "Le logo doit être un PNG (ou WebP) à fond transparent.";
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format refusé", Map.of("file", msg));
        }
        if (bytes.length > MAX_LOGO_BYTES) {
            throw invalid("Le logo dépasse 2 Mo.");
        }
        if (r.info().width() < 300) {
            throw invalid("Le logo doit faire au moins 300 px de large. Reçu : " + r.info().width() + " px.");
        }
        WatermarkSettings s = repository.lockSingleton().orElseThrow();
        String old = s.isCustomLogo() ? s.getLogoPublicId() : null;
        StoredImage logo = storage.uploadBrandAsset(bytes, ImageStorageService.BRAND_FOLDER + "/watermark-" + Tokens.random(8).toLowerCase(Locale.ROOT));
        s.setLogoPublicId(logo.publicId());
        s.setLogoUrl(logo.url());
        s.setCustomLogo(true);
        s.touch();
        repository.saveAndFlush(s);
        if (old != null) {
            events.publishEvent(new ImageDeletionRequested(List.of(old)));
        }
        return toStatus(s);
    }

    /** Revient au logo HITECH DZ intégré à l'application. */
    @Transactional
    public WatermarkStatusDto resetLogo() {
        WatermarkSettings s = repository.lockSingleton().orElseThrow();
        if (s.isCustomLogo() && s.getLogoPublicId() != null) {
            events.publishEvent(new ImageDeletionRequested(List.of(s.getLogoPublicId())));
        }
        s.setLogoPublicId(null);
        s.setLogoUrl(null);
        s.setCustomLogo(false);
        s.touch();
        return toStatus(repository.saveAndFlush(s));
    }

    // ------------------------------------------------------------------ helpers
    static byte[] defaultLogo() {
        try (InputStream in = new ClassPathResource(DEFAULT_LOGO_RESOURCE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Logo par défaut introuvable : " + DEFAULT_LOGO_RESOURCE, e);
        }
    }

    private WatermarkSettings settings() {
        return repository.findById(WatermarkSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Réglages du logo absents (migration V12)."));
    }

    private static Watermark toWatermark(WatermarkSettings s) {
        return new Watermark(s.getLogoPublicId(), s.getPosition().toLowerCase(Locale.ROOT),
                s.getWidthPercent() / 100.0, s.getMarginPercent() / 100.0, s.getOpacity());
    }

    private static WatermarkStatusDto toStatus(WatermarkSettings s) {
        return new WatermarkStatusDto(s.isEnabled(), s.getPosition(), s.getWidthPercent(), s.getMarginPercent(),
                s.getOpacity(), CatalogMapper.deliveryUrl(s.getLogoUrl()), s.isCustomLogo(), POSITIONS, s.getUpdatedAt());
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of("file", message));
    }
}
