package cm.hitechdz.auth.service.payment;

import cm.hitechdz.auth.domain.checkout.ShippingZone;
import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.domain.order.PaymentStatus;
import cm.hitechdz.auth.domain.order.PaymentWebhookEvent;
import cm.hitechdz.auth.dto.order.OrderResponse;
import cm.hitechdz.auth.dto.order.PaymentInitRequest;
import cm.hitechdz.auth.dto.order.PaymentInitResponse;
import cm.hitechdz.auth.dto.order.PaymentStatusResponse;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.checkout.ShippingZoneRepository;
import cm.hitechdz.auth.repository.order.CustomerOrderRepository;
import cm.hitechdz.auth.repository.order.PaymentRepository;
import cm.hitechdz.auth.repository.order.PaymentWebhookEventRepository;
import cm.hitechdz.auth.service.order.OrderAccess;
import cm.hitechdz.auth.service.order.OrderLifecycle;
import cm.hitechdz.auth.service.order.OrderMapper;
import cm.hitechdz.auth.service.order.ShopConfigService;
import cm.hitechdz.auth.util.PhoneUtil;
import cm.hitechdz.auth.util.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Paiements Orange Money / MTN MoMo. Une commande n'est marquée payée que sur preuve du prestataire
 * (API de statut ou notification authentifiée), jamais sur un retour navigateur.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Intervalle minimal entre deux interrogations du prestataire pour un même paiement. */
    public static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(5);

    static final String UNAVAILABLE = "Le paiement en ligne n'est pas disponible pour le moment. Choisissez le paiement à la livraison.";
    static final String IN_PROGRESS = "Un paiement est déjà en cours pour cette commande. Validez-le sur votre téléphone ou attendez son expiration.";
    static final String KEY_REUSED = "Cette clé d'idempotence a déjà été utilisée pour un autre paiement.";

    private final PaymentProviders providers;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final CustomerOrderRepository orderRepository;
    private final ShippingZoneRepository zoneRepository;
    private final ShopConfigService config;
    private final OrderLifecycle lifecycle;
    private final TransactionTemplate tx;

    public PaymentService(PaymentProviders providers, PaymentRepository paymentRepository,
                          PaymentWebhookEventRepository webhookEventRepository, CustomerOrderRepository orderRepository,
                          ShippingZoneRepository zoneRepository, ShopConfigService config, OrderLifecycle lifecycle,
                          PlatformTransactionManager txManager) {
        this.providers = providers;
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.orderRepository = orderRepository;
        this.zoneRepository = zoneRepository;
        this.config = config;
        this.lifecycle = lifecycle;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ initiation
    /**
     * Lance un débit Mobile Money. Même clé + même contenu → le même paiement ; contenu différent → 409.
     * Un seul paiement actif à la fois. En cas d'indisponibilité du prestataire : 503 et rien n'est enregistré.
     */
    @Transactional
    public PaymentInitResponse initiate(String number, OrderAccess access, String idempotencyKey, PaymentInitRequest req) {
        String key = validKey(idempotencyKey);
        PaymentMethod operator = parseOperator(req.operator());
        String phone = PhoneUtil.normalizeCameroon(req.phone());
        if (phone == null) {
            String msg = "Numéro de téléphone camerounais invalide (ex. 6 99 12 34 56).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("phone", msg));
        }
        CustomerOrder order = lockOrder(number);
        access.check(order);
        String requestHash = Tokens.sha256(operator + "|" + phone);

        var existing = paymentRepository.findByOrderIdAndIdempotencyKey(order.getId(), key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, KEY_REUSED);
            }
            return toInitResponse(existing.get(), null);
        }

        PaymentProvider provider = providers.active()
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE));
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> { }
            case CANCELLED -> throw new ApiException(HttpStatus.CONFLICT, "Cette commande est annulée.");
            default -> throw new ApiException(HttpStatus.CONFLICT, order.getPaymentStatus() == PaymentStatus.PAID
                    ? "Cette commande est déjà payée." : "Cette commande est payable à la livraison.");
        }
        for (Payment pending : paymentRepository.findPendingByOrder(order.getId())) {
            refreshLocked(pending, true);
            if (pending.getStatus() == Payment.Status.PENDING) {
                throw new ApiException(HttpStatus.CONFLICT, IN_PROGRESS);
            }
            if (pending.getStatus() == Payment.Status.SUCCEEDED) {
                throw new ApiException(HttpStatus.CONFLICT, "Cette commande est déjà payée.");
            }
        }

        Duration paymentWindow = Duration.ofMinutes(config.settings().getPaymentTimeoutMinutes());
        // La réservation doit couvrir toute la durée du paiement (nouvelle réservation si elle avait expiré : 409 si stock épuisé)
        lifecycle.reReserve(order, paymentWindow.plusMinutes(2));

        Payment p = new Payment();
        p.setId("pay_" + Tokens.random(18));
        p.setOrder(order);
        p.setProvider(provider.name());
        p.setOperator(operator);
        p.setPhone(phone);
        p.setAmount(order.getTotal());
        p.setCurrency(order.getCurrency());
        p.setIdempotencyKey(key);
        p.setRequestHash(requestHash);
        p.setExpiresAt(Instant.now().plus(paymentWindow));

        PaymentProvider.InitResult result = provider.initiate(p, order.getDeliveryName(), order.getDeliveryEmail());
        p.setProviderReference(result.providerReference());
        p.setStatus(result.status());
        p.setMessage(result.message());
        p.setLastCheckedAt(Instant.now());
        paymentRepository.saveAndFlush(p);

        order.setPaymentMethod(operator);
        if (p.getStatus() == Payment.Status.PENDING) {
            order.setPaymentStatus(PaymentStatus.PENDING);
            lifecycle.event(order, "Paiement " + label(operator) + " initié", "Montant : " + p.getAmount() + " " + p.getCurrency(), "client");
        } else {
            order.setPaymentStatus(PaymentStatus.PENDING);
            lifecycle.paymentEnded(order, p);
        }
        return toInitResponse(p, result.checkoutUrl());
    }

    // ------------------------------------------------------------------ statut
    /** Statut d'un paiement ; interroge le prestataire au plus toutes les 5 secondes. */
    @Transactional
    public PaymentStatusResponse status(String number, String paymentId, OrderAccess access) {
        CustomerOrder order = orderRepository.findByNumber(number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Commande introuvable."));
        access.check(order);
        Payment p = paymentRepository.findById(paymentId)
                .filter(x -> x.getOrder().getId().equals(order.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paiement introuvable."));
        if (p.getStatus() == Payment.Status.PENDING && dueForRefresh(p)) {
            lockOrder(number);
            Payment locked = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
            refreshLocked(locked, false);
            p = locked;
        }
        return OrderMapper.toPaymentStatus(p);
    }

    // ------------------------------------------------------------------ changement vers la livraison
    /** Passage au paiement à la livraison : commande impayée, non expédiée, sans paiement en cours, zone compatible. */
    @Transactional
    public OrderResponse switchToCashOnDelivery(String number, OrderAccess access, String paymentMethod) {
        if (!"CASH_ON_DELIVERY".equalsIgnoreCase(paymentMethod == null ? "" : paymentMethod.trim())) {
            String msg = "Seul le passage au paiement à la livraison (CASH_ON_DELIVERY) est possible.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("paymentMethod", msg));
        }
        CustomerOrder order = lockOrder(number);
        access.check(order);
        if (order.getPaymentMethod() == PaymentMethod.CASH_ON_DELIVERY) {
            return OrderMapper.toResponse(order, null);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "Le mode de paiement ne peut plus être modifié pour cette commande.");
        }
        for (Payment pending : paymentRepository.findPendingByOrder(order.getId())) {
            refreshLocked(pending, true);   // vérification auprès du prestataire avant de changer
            if (pending.getStatus() == Payment.Status.PENDING) {
                throw new ApiException(HttpStatus.CONFLICT, IN_PROGRESS);
            }
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentStatus() == PaymentStatus.PAID) {
            // le paiement vient d'aboutir pendant la vérification
            return OrderMapper.toResponse(order, null);
        }
        ShippingZone zone = zoneRepository.findById(order.getDeliveryZoneId()).orElse(null);
        if (zone == null || !zone.isCodAllowed()) {
            throw new ApiException(HttpStatus.CONFLICT, "Le paiement à la livraison n'est pas disponible pour cette zone.");
        }
        lifecycle.reReserve(order, null);   // 409 si le stock a été vendu entre-temps
        order.setExpiresAt(null);
        order.setPaymentMethod(PaymentMethod.CASH_ON_DELIVERY);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        lifecycle.event(order, "Paiement à la livraison choisi", null, "client");
        orderRepository.flush();
        return OrderMapper.toResponse(order, null);
    }

    // ------------------------------------------------------------------ webhooks
    /**
     * Notification d'un prestataire : authentifiée (signature ou relecture du statut auprès du prestataire),
     * anti-rejeu, montant et devise comparés. Idempotente : doublons et retards sans effet.
     * @return true si la notification a été appliquée, false si ignorée (doublon, paiement inconnu)
     */
    public boolean webhook(String providerName, Map<String, String> headers, String rawBody) {
        PaymentProvider provider = providers.byName(providerName)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Prestataire inconnu."));
        PaymentProvider.VerifiedEvent event = provider.verifyWebhook(headers, rawBody);
        Boolean applied = tx.execute(status -> {
            try {
                webhookEventRepository.saveAndFlush(new PaymentWebhookEvent(provider.name(), event.eventKey()));
            } catch (DataIntegrityViolationException duplicate) {
                status.setRollbackOnly();
                return false;
            }
            Payment p = paymentRepository.findByProviderAndProviderReference(provider.name(), event.providerReference())
                    .orElse(null);
            if (p == null) {
                log.warn("Notification {} pour une référence inconnue : {}", provider.name(), event.providerReference());
                return false;
            }
            orderRepository.findByIdForUpdate(p.getOrder().getId());
            Payment locked = paymentRepository.findByIdForUpdate(p.getId()).orElseThrow();
            apply(locked, event.status(), event.amount(), event.currency(), event.message());
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    // ------------------------------------------------------------------ tâches planifiées
    /** Paiements en attente arrivés à échéance : dernière vérification puis EXPIRED. */
    public void expirePayments() {
        for (String id : paymentRepository.findExpiredPending(Instant.now())) {
            try {
                tx.executeWithoutResult(s -> {
                    Payment p = paymentRepository.findById(id).orElse(null);
                    if (p == null || p.getStatus() != Payment.Status.PENDING) return;
                    orderRepository.findByIdForUpdate(p.getOrder().getId());
                    Payment locked = paymentRepository.findByIdForUpdate(id).orElseThrow();
                    refreshLocked(locked, true);
                    if (locked.getStatus() == Payment.Status.PENDING && locked.getExpiresAt().isBefore(Instant.now())) {
                        locked.setMessage("Paiement non validé dans le délai.");
                        apply(locked, Payment.Status.EXPIRED, null, null, null);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Expiration du paiement {} impossible : {}", id, e.toString());
            }
        }
    }

    /** Réservations de stock expirées sans paiement en cours : stock libéré, commande conservée. */
    public void releaseExpiredReservations() {
        for (var orderId : orderRepository.findExpiredReservations(OrderStatus.PENDING_PAYMENT, Instant.now())) {
            try {
                tx.executeWithoutResult(s -> {
                    CustomerOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
                    if (order == null || order.getExpiresAt() == null || order.getExpiresAt().isAfter(Instant.now())) return;
                    if (!paymentRepository.findPendingByOrder(orderId).isEmpty()) return;   // paiement en cours
                    lifecycle.reservationExpired(order);
                });
            } catch (RuntimeException e) {
                log.error("Libération de la réservation {} impossible : {}", orderId, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ cœur
    private boolean dueForRefresh(Payment p) {
        return p.getLastCheckedAt() == null
                || p.getLastCheckedAt().plus(MIN_REFRESH_INTERVAL).isBefore(Instant.now());
    }

    /** Interroge le prestataire (paiement et commande verrouillés par l'appelant). */
    private void refreshLocked(Payment p, boolean force) {
        if (p.getStatus() != Payment.Status.PENDING || p.getProviderReference() == null) {
            return;
        }
        if (!force && !dueForRefresh(p)) {
            return;
        }
        PaymentProvider provider = providers.byName(p.getProvider()).orElse(null);
        if (provider == null) {
            return;
        }
        p.setLastCheckedAt(Instant.now());
        try {
            PaymentProvider.ProviderStatus s = provider.fetchStatus(p.getProviderReference());
            apply(p, s.status(), s.amount(), s.currency(), s.message());
        } catch (ProviderUnavailableException e) {
            log.warn("Statut du paiement {} indisponible pour le moment", p.getId());
        }
    }

    /** Applique un statut prouvé par le prestataire. */
    private void apply(Payment p, Payment.Status newStatus, Long amount, String currency, String message) {
        if (newStatus == null || newStatus == Payment.Status.PENDING) {
            return;
        }
        if (p.getStatus() == Payment.Status.SUCCEEDED) {
            return;   // déjà traité (notification en double ou tardive)
        }
        CustomerOrder order = p.getOrder();
        if (newStatus == Payment.Status.SUCCEEDED) {
            if ((amount != null && amount != p.getAmount())
                    || (currency != null && !currency.isBlank() && !currency.equalsIgnoreCase(p.getCurrency()))) {
                log.error("Montant ou devise incohérent pour le paiement {} : {} {} au lieu de {} {}",
                        p.getId(), amount, currency, p.getAmount(), p.getCurrency());
                lifecycle.event(order, "Paiement à vérifier : montant ou devise incohérent",
                        "Reçu " + amount + " " + currency + ", attendu " + p.getAmount() + " " + p.getCurrency(), "système");
                return;
            }
            boolean wasClosed = p.getStatus() != Payment.Status.PENDING;
            p.setStatus(Payment.Status.SUCCEEDED);
            p.setMessage(wasClosed ? "Paiement confirmé après expiration." : "Paiement confirmé.");
            lifecycle.paymentSucceeded(order, p);
            return;
        }
        if (p.getStatus() != Payment.Status.PENDING) {
            return;   // déjà clôturé
        }
        p.setStatus(newStatus);
        if (message != null && !message.isBlank()) {
            p.setMessage(message.length() > 300 ? message.substring(0, 300) : message);
        } else if (newStatus == Payment.Status.FAILED) {
            p.setMessage("Paiement refusé ou annulé sur le téléphone.");
        }
        lifecycle.paymentEnded(order, p);
    }

    private CustomerOrder lockOrder(String number) {
        return orderRepository.findByNumberForUpdate(number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Commande introuvable."));
    }

    private PaymentInitResponse toInitResponse(Payment p, String checkoutUrl) {
        String message = switch (p.getStatus()) {
            case PENDING -> "Validez le paiement sur votre téléphone (" + label(p.getOperator()) + ").";
            default -> p.getMessage();
        };
        return new PaymentInitResponse(p.getId(), p.getOrder().getNumber(), p.getOperator().name(), p.getStatus().name(),
                p.getAmount(), p.getCurrency(), p.getExpiresAt(), message, checkoutUrl);
    }

    private static PaymentMethod parseOperator(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (v.equals("ORANGE_MONEY")) return PaymentMethod.ORANGE_MONEY;
        if (v.equals("MTN_MOMO")) return PaymentMethod.MTN_MOMO;
        String msg = "Opérateur invalide (ORANGE_MONEY ou MTN_MOMO).";
        throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("operator", msg));
    }

    private static String validKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() < 8 || key.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "En-tête Idempotency-Key obligatoire (identifiant unique de 8 à 80 caractères).");
        }
        return key.trim();
    }

    private static String label(PaymentMethod m) {
        return m == PaymentMethod.ORANGE_MONEY ? "Orange Money" : m == PaymentMethod.MTN_MOMO ? "MTN MoMo" : m.name();
    }
}
