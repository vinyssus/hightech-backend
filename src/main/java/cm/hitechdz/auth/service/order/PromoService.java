package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.checkout.PromoCode;
import cm.hitechdz.auth.domain.checkout.PromoRedemption;
import cm.hitechdz.auth.domain.checkout.PromoType;
import cm.hitechdz.auth.dto.order.PromoDto;
import cm.hitechdz.auth.dto.order.PromoRequest;
import cm.hitechdz.auth.dto.order.PromoSummary;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.checkout.PromoCodeRepository;
import cm.hitechdz.auth.repository.checkout.PromoRedemptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Codes promo réels : actif, dates, minimum d'achat, plafond, nombre d'utilisations global et par client.
 * Aucun code n'existe par défaut : ils sont créés dans l'admin.
 */
@Service
public class PromoService {

    static final String INVALID = "Code promo invalide.";
    static final String NOT_STARTED = "Ce code promo n'est pas encore valable.";
    static final String EXPIRED = "Ce code promo a expiré.";
    static final String EXHAUSTED = "Ce code promo n'est plus disponible.";
    static final String ALREADY_USED = "Vous avez déjà utilisé ce code promo.";
    static final String NOT_FOUND = "Code promo introuvable.";

    /** Résultat : remise sur le sous-total et/ou livraison offerte. */
    public record Applied(PromoCode promo, long discount, boolean freeShipping) {
        public PromoSummary summary() {
            return new PromoSummary(promo.getCode(), promo.getType().name(), promo.getDescription(), discount);
        }
    }

    private final PromoCodeRepository promoRepository;
    private final PromoRedemptionRepository redemptionRepository;

    public PromoService(PromoCodeRepository promoRepository, PromoRedemptionRepository redemptionRepository) {
        this.promoRepository = promoRepository;
        this.redemptionRepository = redemptionRepository;
    }

    /**
     * Vérifie un code pour un sous-total. customerKey (compte ou téléphone) active la limite par client ;
     * null au stade du devis invité (la limite est alors vérifiée à la commande).
     * @return null si aucun code n'est fourni ; 400 (fieldErrors.promoCode) si le code est refusé
     */
    @Transactional(readOnly = true)
    public Applied evaluate(String rawCode, long subtotal, String customerKey) {
        String code = PromoCode.normalize(rawCode);
        if (code == null || code.isEmpty()) {
            return null;
        }
        PromoCode promo = promoRepository.findByCode(code).orElseThrow(() -> refuse(INVALID));
        return check(promo, subtotal, customerKey);
    }

    /** À la commande : verrouille le code, revérifie les limites et enregistre l'utilisation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied redeem(String rawCode, long subtotal, UUID orderId, UUID userId, String customerKey) {
        String code = PromoCode.normalize(rawCode);
        if (code == null || code.isEmpty()) {
            return null;
        }
        PromoCode promo = promoRepository.findByCodeForUpdate(code).orElseThrow(() -> refuse(INVALID));
        Applied applied = check(promo, subtotal, customerKey);
        promo.setUsedCount(promo.getUsedCount() + 1);
        redemptionRepository.save(new PromoRedemption(promo.getId(), orderId, userId, customerKey));
        return applied;
    }

    /** Annulation de commande : l'utilisation du code est rendue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID orderId) {
        redemptionRepository.findByOrderId(orderId).ifPresent(r -> {
            promoRepository.findById(r.getPromoId())
                    .ifPresent(p -> p.setUsedCount(Math.max(0, p.getUsedCount() - 1)));
            redemptionRepository.delete(r);
        });
    }

    private Applied check(PromoCode promo, long subtotal, String customerKey) {
        Instant now = Instant.now();
        if (!promo.isActive()) throw refuse(INVALID);
        if (promo.getStartsAt() != null && now.isBefore(promo.getStartsAt())) throw refuse(NOT_STARTED);
        if (promo.getEndsAt() != null && !now.isBefore(promo.getEndsAt())) throw refuse(EXPIRED);
        if (promo.getMinSubtotal() != null && subtotal < promo.getMinSubtotal()) {
            throw refuse("Ce code est valable à partir de " + fcfa(promo.getMinSubtotal()) + " d'achat.");
        }
        if (promo.getMaxUses() != null && promo.getUsedCount() >= promo.getMaxUses()) throw refuse(EXHAUSTED);
        if (customerKey != null && promo.getMaxUsesPerCustomer() != null
                && redemptionRepository.countByPromoIdAndCustomerKey(promo.getId(), customerKey)
                >= promo.getMaxUsesPerCustomer()) {
            throw refuse(ALREADY_USED);
        }
        long discount = switch (promo.getType()) {
            case PERCENT -> subtotal * promo.getValue() / 100;
            case FIXED -> promo.getValue();
            case FREE_SHIPPING -> 0;
        };
        if (promo.getMaxDiscount() != null) {
            discount = Math.min(discount, promo.getMaxDiscount());
        }
        discount = Math.max(0, Math.min(discount, subtotal));
        return new Applied(promo, discount, promo.getType() == PromoType.FREE_SHIPPING);
    }

    private static ApiException refuse(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of("promoCode", message));
    }

    private static String fcfa(long amount) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(amount).replace(' ', ' ') + " FCFA";
    }

    // ------------------------------------------------------------------ admin
    @Transactional(readOnly = true)
    public List<PromoDto> adminList() {
        return promoRepository.findAllOrdered().stream().map(PromoService::toDto).toList();
    }

    @Transactional
    public PromoDto create(PromoRequest req) {
        String code = PromoCode.normalize(req.code());
        if (promoRepository.existsByCode(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ce code promo existe déjà.", Map.of("code", "Ce code promo existe déjà."));
        }
        PromoCode p = new PromoCode();
        p.setCode(code);
        apply(p, req);
        return toDto(promoRepository.saveAndFlush(p));
    }

    @Transactional
    public PromoDto update(String code, PromoRequest req) {
        PromoCode p = promoRepository.findByCode(PromoCode.normalize(code))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        apply(p, req);
        promoRepository.flush();
        return toDto(p);
    }

    @Transactional
    public void delete(String code) {
        PromoCode p = promoRepository.findByCode(PromoCode.normalize(code))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (p.getUsedCount() > 0) {
            // Les commandes passées gardent leur trace : on désactive au lieu de supprimer.
            p.setActive(false);
            return;
        }
        promoRepository.delete(p);
    }

    private static void apply(PromoCode p, PromoRequest req) {
        PromoType type;
        try {
            type = PromoType.valueOf(req.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String msg = "Type invalide (PERCENT, FIXED ou FREE_SHIPPING).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("type", msg));
        }
        long value = req.value() == null ? 0 : req.value();
        if (type == PromoType.PERCENT && (value < 1 || value > 100)) {
            String msg = "Le pourcentage doit être compris entre 1 et 100.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("value", msg));
        }
        if (type == PromoType.FIXED && value < 1) {
            String msg = "Le montant de la remise doit être supérieur à 0.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("value", msg));
        }
        if (req.startsAt() != null && req.endsAt() != null && !req.endsAt().isAfter(req.startsAt())) {
            String msg = "La date de fin doit être après la date de début.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("endsAt", msg));
        }
        p.setType(type);
        p.setValue(type == PromoType.FREE_SHIPPING ? 0 : value);
        p.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        p.setActive(Boolean.TRUE.equals(req.active()));
        p.setStartsAt(req.startsAt());
        p.setEndsAt(req.endsAt());
        p.setMinSubtotal(req.minSubtotal());
        p.setMaxDiscount(req.maxDiscount());
        p.setMaxUses(req.maxUses());
        p.setMaxUsesPerCustomer(req.maxUsesPerCustomer());
    }

    static PromoDto toDto(PromoCode p) {
        return new PromoDto(p.getCode(), p.getType().name(), p.getValue(), p.getDescription(), p.isActive(),
                p.getStartsAt(), p.getEndsAt(), p.getMinSubtotal(), p.getMaxDiscount(), p.getMaxUses(),
                p.getMaxUsesPerCustomer(), p.getUsedCount(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
