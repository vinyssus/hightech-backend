package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.checkout.ShippingZone;
import cm.hitechdz.auth.domain.checkout.ShopSettings;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.dto.order.CheckoutOptionsResponse;
import cm.hitechdz.auth.dto.order.ShippingZoneDto;
import cm.hitechdz.auth.dto.order.ShippingZoneRequest;
import cm.hitechdz.auth.dto.order.ShopSettingsDto;
import cm.hitechdz.auth.dto.order.ZoneOption;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.checkout.ShippingZoneRepository;
import cm.hitechdz.auth.repository.checkout.ShopSettingsRepository;
import cm.hitechdz.auth.service.payment.PaymentProviders;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Zones de livraison et réglages de vente (gérés dans l'admin). */
@Service
public class ShopConfigService {

    static final String ZONE_NOT_FOUND = "Zone de livraison introuvable.";

    private final ShippingZoneRepository zoneRepository;
    private final ShopSettingsRepository settingsRepository;
    private final PaymentProviders providers;

    public ShopConfigService(ShippingZoneRepository zoneRepository, ShopSettingsRepository settingsRepository,
                             PaymentProviders providers) {
        this.zoneRepository = zoneRepository;
        this.settingsRepository = settingsRepository;
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public CheckoutOptionsResponse options() {
        ShopSettings s = settings();
        List<ZoneOption> zones = zoneRepository.findActive().stream()
                .map(z -> new ZoneOption(z.getId(), z.getLabel(), z.getDelay(), z.getFee(), z.isCodAllowed(),
                        z.isRequiresCity(), z.isPickup()))
                .toList();
        List<String> methods = new ArrayList<>();
        if (zones.stream().anyMatch(ZoneOption::codAllowed)) {
            methods.add(PaymentMethod.CASH_ON_DELIVERY.name());
        }
        if (onlinePaymentAvailable()) {
            methods.add(PaymentMethod.ORANGE_MONEY.name());
            methods.add(PaymentMethod.MTN_MOMO.name());
        }
        return new CheckoutOptionsResponse("XAF", s.getFreeShippingFrom(), zones, methods);
    }

    public boolean onlinePaymentAvailable() {
        return providers.active().isPresent();
    }

    @Transactional(readOnly = true)
    public ShopSettings settings() {
        return settingsRepository.findById(ShopSettings.SINGLETON_ID).orElseGet(ShopSettings::new);
    }

    /** Zone active, sinon 400 sur le champ indiqué. */
    @Transactional(readOnly = true)
    public ShippingZone activeZone(String zoneId, String field) {
        ShippingZone zone = zoneId == null ? null : zoneRepository.findById(zoneId.trim()).orElse(null);
        if (zone == null || !zone.isActive()) {
            String msg = "Cette zone de livraison n'est pas disponible.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
        }
        return zone;
    }

    /** Frais de livraison : zone, puis livraison offerte (seuil après remise ou code promo). */
    public long shippingFee(ShippingZone zone, long subtotalAfterDiscount, boolean freeShippingPromo) {
        if (freeShippingPromo) {
            return 0;
        }
        Long threshold = settings().getFreeShippingFrom();
        if (threshold != null && subtotalAfterDiscount >= threshold) {
            return 0;
        }
        return zone.getFee();
    }

    // ------------------------------------------------------------------ admin
    @Transactional(readOnly = true)
    public List<ShippingZoneDto> adminZones() {
        return zoneRepository.findAllOrdered().stream().map(ShopConfigService::toDto).toList();
    }

    @Transactional
    public ShippingZoneDto createZone(ShippingZoneRequest req) {
        String id = SlugUtil.slugify(req.id() == null || req.id().isBlank() ? req.label() : req.id());
        if (id.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Identifiant de zone invalide.", Map.of("id", "Identifiant invalide."));
        }
        if (zoneRepository.existsById(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Cette zone existe déjà.", Map.of("id", "Cette zone existe déjà."));
        }
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        if (req.label() == null || req.label().isBlank()) errors.put("label", "Le libellé est obligatoire.");
        if (req.fee() == null) errors.put("fee", "Les frais sont obligatoires (0 pour gratuit).");
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Veuillez corriger les champs indiqués.", errors);
        }
        ShippingZone z = new ShippingZone();
        z.setId(id);
        apply(z, req);
        return toDto(zoneRepository.saveAndFlush(z));
    }

    @Transactional
    public ShippingZoneDto updateZone(String id, ShippingZoneRequest req) {
        ShippingZone z = zoneRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ZONE_NOT_FOUND));
        apply(z, req);
        zoneRepository.flush();
        return toDto(z);
    }

    @Transactional
    public void deleteZone(String id) {
        ShippingZone z = zoneRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ZONE_NOT_FOUND));
        // Les commandes gardent une copie de la zone : la suppression ne casse pas l'historique.
        zoneRepository.delete(z);
    }

    @Transactional(readOnly = true)
    public ShopSettingsDto adminSettings() {
        ShopSettings s = settings();
        return new ShopSettingsDto(s.getFreeShippingFrom(), s.getQuoteValidityMinutes(), s.getReservationMinutes(),
                s.getPaymentTimeoutMinutes());
    }

    @Transactional
    public ShopSettingsDto updateSettings(ShopSettingsDto req) {
        ShopSettings s = settingsRepository.findById(ShopSettings.SINGLETON_ID).orElseGet(ShopSettings::new);
        s.setFreeShippingFrom(req.freeShippingFrom());
        if (req.quoteValidityMinutes() != null) s.setQuoteValidityMinutes(req.quoteValidityMinutes());
        if (req.reservationMinutes() != null) s.setReservationMinutes(req.reservationMinutes());
        if (req.paymentTimeoutMinutes() != null) s.setPaymentTimeoutMinutes(req.paymentTimeoutMinutes());
        if (s.getReservationMinutes() <= s.getPaymentTimeoutMinutes()) {
            String msg = "La réservation doit durer plus longtemps que le délai de paiement.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("reservationMinutes", msg));
        }
        settingsRepository.saveAndFlush(s);
        return adminSettings();
    }

    private static void apply(ShippingZone z, ShippingZoneRequest req) {
        if (req.label() != null) {
            if (req.label().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Le libellé est obligatoire.",
                        Map.of("label", "Le libellé est obligatoire."));
            }
            z.setLabel(req.label().trim());
        }
        if (req.fee() != null) z.setFee(req.fee());
        if (req.delay() != null) z.setDelay(req.delay().isBlank() ? null : req.delay().trim());
        if (req.requiresCity() != null) z.setRequiresCity(req.requiresCity());
        if (req.pickup() != null) z.setPickup(req.pickup());
        if (req.codAllowed() != null) z.setCodAllowed(req.codAllowed());
        if (req.active() != null) z.setActive(req.active());
        if (req.position() != null) z.setPosition(req.position());
    }

    private static ShippingZoneDto toDto(ShippingZone z) {
        return new ShippingZoneDto(z.getId(), z.getLabel(), z.getFee(), z.getDelay(), z.isRequiresCity(),
                z.isPickup(), z.isCodAllowed(), z.isActive(), z.getPosition(), z.getUpdatedAt());
    }
}
