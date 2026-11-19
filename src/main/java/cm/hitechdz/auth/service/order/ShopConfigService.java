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
import cm.hitechdz.auth.repository.order.CustomerOrderRepository;
import cm.hitechdz.auth.service.payment.PaymentProviders;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Zones de livraison et réglages de vente (gérés dans l'admin). */
@Service
public class ShopConfigService {

    static final String ZONE_NOT_FOUND = "Zone de livraison introuvable.";

    private final ShippingZoneRepository zoneRepository;
    private final ShopSettingsRepository settingsRepository;
    static final String ZONE_UNAVAILABLE = "Zone de livraison indisponible";
    static final long MAX_FEE = 100_000;

    private final PaymentProviders providers;
    private final CustomerOrderRepository orderRepository;

    public ShopConfigService(ShippingZoneRepository zoneRepository, ShopSettingsRepository settingsRepository,
                             PaymentProviders providers, CustomerOrderRepository orderRepository) {
        this.zoneRepository = zoneRepository;
        this.settingsRepository = settingsRepository;
        this.providers = providers;
        this.orderRepository = orderRepository;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, ZONE_UNAVAILABLE, Map.of(field, ZONE_UNAVAILABLE));
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
        String id = SlugUtil.slugify(req.id() == null || req.id().isBlank()
                ? (req.label() == null ? "" : req.label()) : req.id());
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.fee() == null) errors.put("fee", "Les frais sont obligatoires (0 pour gratuit).");
        if (id.isEmpty() && req.label() != null && !req.label().isBlank()) errors.put("id", "Identifiant invalide.");
        ShippingZone z = new ShippingZone();
        z.setId(id);
        z.setActive(true);
        apply(z, req);
        validate(z, errors);
        if (zoneRepository.existsById(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Cette zone existe déjà.", Map.of("id", "Cette zone existe déjà."));
        }
        if (req.position() == null) z.setPosition(zoneRepository.maxPosition() + 1);
        return toDto(zoneRepository.saveAndFlush(z));
    }

    @Transactional
    public ShippingZoneDto updateZone(String id, ShippingZoneRequest req) {
        ShippingZone z = zoneRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ZONE_NOT_FOUND));
        apply(z, req);
        validate(z, new LinkedHashMap<>());
        zoneRepository.flush();
        return toDto(z);
    }

    /**
     * Zone jamais utilisée : supprimée (vide). Zone présente dans des commandes : seulement
     * désactivée et renvoyée, les commandes gardant leur copie de la zone.
     */
    @Transactional
    public Optional<ShippingZoneDto> deleteZone(String id) {
        ShippingZone z = zoneRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ZONE_NOT_FOUND));
        if (orderRepository.existsByDeliveryZoneId(z.getId())) {
            z.setActive(false);
            zoneRepository.flush();
            return Optional.of(toDto(z));
        }
        zoneRepository.delete(z);
        return Optional.empty();
    }

    @Transactional
    public List<ShippingZoneDto> reorderZones(List<String> ids) {
        List<ShippingZone> all = zoneRepository.findAllOrdered();
        Map<String, ShippingZone> byId = new LinkedHashMap<>();
        all.forEach(z -> byId.put(z.getId(), z));
        List<ShippingZone> ordered = new ArrayList<>();
        for (String raw : ids) {
            ShippingZone z = raw == null ? null : byId.remove(raw.trim());
            if (z == null) {
                String msg = raw != null && all.stream().anyMatch(a -> a.getId().equals(raw.trim()))
                        ? "Zone en double : " + raw + "." : "Zone inconnue : " + raw + ".";
                throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("ids", msg));
            }
            ordered.add(z);
        }
        ordered.addAll(byId.values());
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i + 1);
        }
        zoneRepository.flush();
        return adminZones();
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
        if (req.label() != null) z.setLabel(req.label().trim());
        if (req.fee() != null) z.setFee(req.fee());
        if (req.delay() != null) z.setDelay(req.delay().trim());
        if (req.requiresCity() != null) z.setRequiresCity(req.requiresCity());
        if (req.pickup() != null) z.setPickup(req.pickup());
        if (req.codAllowed() != null) z.setCodAllowed(req.codAllowed());
        if (req.active() != null) z.setActive(req.active());
        if (req.position() != null) z.setPosition(req.position());
    }

    /** Règles d'une zone complète (création comme modification), puis unicité du libellé (409). */
    private void validate(ShippingZone z, Map<String, String> errors) {
        String label = z.getLabel();
        if (label == null || label.isBlank()) {
            errors.put("label", "Le libellé est obligatoire.");
        } else if (label.length() < 2 || label.length() > 80) {
            errors.put("label", "Le libellé doit contenir entre 2 et 80 caractères.");
        }
        if (z.getFee() < 0) errors.putIfAbsent("fee", "Les frais doivent être positifs ou nuls.");
        if (z.getFee() > MAX_FEE) errors.putIfAbsent("fee", "Les frais ne peuvent pas dépasser 100 000 FCFA.");
        String delay = z.getDelay();
        if (delay == null || delay.isBlank()) {
            errors.put("delay", "Le délai est obligatoire (ex. « 24-48h »).");
        } else if (delay.length() < 2 || delay.length() > 40) {
            errors.put("delay", "Le délai doit contenir entre 2 et 40 caractères.");
        }
        if (z.isPickup() && z.isRequiresCity()) {
            String msg = "Un retrait en boutique ne peut pas exiger la ville du client.";
            errors.put("pickup", msg);
            errors.put("requiresCity", msg);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Veuillez corriger les champs indiqués.", errors);
        }
        boolean duplicate = zoneRepository.findByLabelIgnoreCase(label).stream()
                .anyMatch(other -> !other.getId().equals(z.getId()));
        if (duplicate) {
            String msg = "Une zone porte déjà ce libellé.";
            throw new ApiException(HttpStatus.CONFLICT, msg, Map.of("label", msg));
        }
    }

    private static ShippingZoneDto toDto(ShippingZone z) {
        return new ShippingZoneDto(z.getId(), z.getLabel(), z.getFee(), z.getDelay(), z.isRequiresCity(),
                z.isPickup(), z.isCodAllowed(), z.isActive(), z.getPosition(), z.getCreatedAt(), z.getUpdatedAt());
    }
}
