package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.checkout.CheckoutQuote;
import cm.hitechdz.auth.domain.checkout.ShippingZone;
import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.domain.order.PaymentStatus;
import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.catalog.CartItemInput;
import cm.hitechdz.auth.dto.order.AdminOrderResponse;
import cm.hitechdz.auth.dto.order.CheckoutConflictResponse;
import cm.hitechdz.auth.dto.order.CreateOrderRequest;
import cm.hitechdz.auth.dto.order.DeliveryInput;
import cm.hitechdz.auth.dto.order.OrderResponse;
import cm.hitechdz.auth.dto.order.QuoteIssue;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.exception.CheckoutConflictException;
import cm.hitechdz.auth.repository.checkout.CheckoutQuoteRepository;
import cm.hitechdz.auth.repository.order.CustomerOrderRepository;
import cm.hitechdz.auth.repository.order.PaymentRepository;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.InventoryService.PricedLine;
import cm.hitechdz.auth.service.catalog.InventoryService.ReserveItem;
import cm.hitechdz.auth.service.catalog.InventoryService.ReservedLine;
import cm.hitechdz.auth.util.PhoneUtil;
import cm.hitechdz.auth.util.Tokens;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Création, suivi, annulation et administration des commandes. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    static final String NOT_FOUND = "Commande introuvable.";
    static final String QUOTE_EXPIRED = "Votre devis a expiré. Vérifiez votre panier puis confirmez de nouveau.";
    static final String QUOTE_MISMATCH = "Le panier a changé depuis le devis. Vérifiez votre panier puis confirmez de nouveau.";
    static final String CART_CHANGED = "Certains prix ou stocks ont changé. Vérifiez votre panier puis confirmez de nouveau.";
    static final String KEY_REUSED = "Cette clé d'idempotence a déjà été utilisée pour une autre commande.";
    static final String BAD_KEY = "En-tête Idempotency-Key invalide (identifiant unique de 8 à 80 caractères attendu).";
    static final Duration GUEST_TOKEN_TTL = Duration.ofDays(90);

    /** Résultat de création : created=false quand la même clé rejoue une commande existante. */
    public record Creation(OrderResponse order, boolean created) {}

    private final CustomerOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CheckoutQuoteRepository quoteRepository;
    private final InventoryService inventory;
    private final ShopConfigService config;
    private final PromoService promos;
    private final OrderLifecycle lifecycle;
    private final ObjectMapper json;

    public OrderService(CustomerOrderRepository orderRepository, PaymentRepository paymentRepository,
                        CheckoutQuoteRepository quoteRepository, InventoryService inventory, ShopConfigService config,
                        PromoService promos, OrderLifecycle lifecycle, ObjectMapper json) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.quoteRepository = quoteRepository;
        this.inventory = inventory;
        this.config = config;
        this.promos = promos;
        this.lifecycle = lifecycle;
        this.json = json;
    }

    // ------------------------------------------------------------------ création
    /**
     * Crée la commande à partir d'un devis valide. Prix, remise, frais et total sont recalculés
     * sous verrou ; s'ils diffèrent du devis, 409 avec le panier recalculé (rien n'est réservé).
     */
    @Transactional
    public Creation create(CreateOrderRequest req, String idempotencyKey, UUID userId) {
        String key = validKey(idempotencyKey);
        String requestHash = requestHash(req, userId);

        var existing = orderRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, userId);
        }

        // 1. Coordonnées et mode de paiement
        DeliveryInput d = req.delivery();
        ShippingZone zone = config.activeZone(d.zoneId(), "delivery.zoneId");
        String city = CheckoutService.requireCity(zone, d.city(), "delivery.city");
        Map<String, String> errors = new LinkedHashMap<>();
        String phone = PhoneUtil.normalizeCameroon(d.phone());
        if (phone == null) {
            errors.put("delivery.phone", "Numéro de téléphone camerounais invalide (ex. 6 99 12 34 56).");
        }
        if (!zone.isPickup() && (d.address() == null || d.address().isBlank())) {
            errors.put("delivery.address", "Indiquez votre adresse (quartier et repère).");
        }
        PaymentMethod method = parseMethod(req.paymentMethod());
        if (method == null) {
            errors.put("paymentMethod", "Mode de paiement invalide (CASH_ON_DELIVERY, ORANGE_MONEY ou MTN_MOMO).");
        } else if (method == PaymentMethod.CASH_ON_DELIVERY && !zone.isCodAllowed()) {
            errors.put("paymentMethod", "Le paiement à la livraison n'est pas disponible pour cette zone.");
        } else if (method.online() && !config.onlinePaymentAvailable()) {
            errors.put("paymentMethod", "Le paiement en ligne n'est pas encore disponible. Choisissez le paiement à la livraison.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Certains champs sont invalides.", errors);
        }

        // 2. Devis : existant, non expiré, portant sur le même panier
        CheckoutQuote quote = quoteRepository.findById(req.quoteId()).orElse(null);
        if (quote == null || quote.isExpired(Instant.now())) {
            throw conflict(QUOTE_EXPIRED, "QUOTE_EXPIRED", req);
        }
        String cartHash = CheckoutService.requestHash(
                req.items().stream().map(i -> new CheckoutService.Item(i.productId(), i.sku(), i.qty())).toList(),
                zone.getId(), city, req.promoCode());
        if (!cartHash.equals(quote.getRequestHash())) {
            throw conflict(QUOTE_MISMATCH, "QUOTE_MISMATCH", req);
        }

        // 3. Réservation verrouillée du stock + recalcul des montants
        CustomerOrder order = new CustomerOrder();
        List<ReservedLine> reserved;
        try {
            reserved = inventory.reserve(req.items().stream()
                    .map(i -> new ReserveItem(i.productId(), blankToNull(i.sku()), i.qty())).toList());
        } catch (ApiException e) {
            throw conflict(CART_CHANGED, "STOCK_CHANGED", req);
        }
        long subtotal = reserved.stream().mapToLong(l -> l.unitPrice() * l.qty()).sum();
        String customerKey = userId != null ? CheckoutService.customerKeyForUser(userId) : "phone:" + phone;
        PromoService.Applied promo = promos.evaluate(req.promoCode(), subtotal, customerKey);
        long discount = promo == null ? 0 : promo.discount();
        long shipping = config.shippingFee(zone, subtotal - discount, promo != null && promo.freeShipping());
        long total = subtotal - discount + shipping;
        if (subtotal != quote.getSubtotal() || discount != quote.getDiscount() || shipping != quote.getShipping()
                || total != quote.getTotal()) {
            throw conflict(CART_CHANGED, "PRICE_CHANGED", req);   // rollback : stock et code promo rendus
        }

        // 4. Commande
        order.setNumber(newNumber());
        order.setUserId(userId);
        order.setIdempotencyKey(key);
        order.setRequestHash(requestHash);
        reserved.forEach(l -> order.addItem(l.toOrderItem()));
        order.setSubtotal(subtotal);
        order.setDiscount(discount);
        order.setShipping(shipping);
        order.setTotal(total);
        order.setPromoCode(promo == null ? null : promo.promo().getCode());
        order.setDeliveryName(d.name().trim());
        order.setDeliveryPhone(phone);
        order.setDeliveryEmail(blankToNull(d.email()));
        order.setDeliveryZoneId(zone.getId());
        order.setDeliveryZoneLabel(zone.getLabel());
        order.setDeliveryCity(city != null ? city : zone.isPickup() ? null : zone.getLabel());
        order.setDeliveryAddress(blankToNull(d.address()));
        order.setDeliveryNotes(d.notes() == null ? null : d.notes().trim());
        order.setPaymentMethod(method);
        order.setStockReserved(true);
        if (method == PaymentMethod.CASH_ON_DELIVERY) {
            order.setStatus(OrderStatus.PENDING_CONFIRMATION);
            order.setPaymentStatus(PaymentStatus.UNPAID);
        } else {
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.setExpiresAt(Instant.now().plus(Duration.ofMinutes(config.settings().getReservationMinutes())));
        }
        lifecycle.event(order, "Commande enregistrée", null, userId == null ? "invité" : "client");

        String guestToken = null;
        if (userId == null) {
            guestToken = Tokens.random(32);
            order.setGuestTokenHash(Tokens.sha256(guestToken));
            order.setGuestTokenExpiresAt(Instant.now().plus(GUEST_TOKEN_TTL));
        }
        orderRepository.saveAndFlush(order);
        if (promo != null) {
            // Verrou sur le code, nouvelle vérification des limites et enregistrement de l'utilisation
            promos.redeem(req.promoCode(), subtotal, order.getId(), userId, customerKey);
        }
        log.info("Commande {} créée ({} {}, {})", order.getNumber(), order.getTotal(), order.getCurrency(), method);
        return new Creation(OrderMapper.toResponse(order, guestToken), true);
    }

    /** Même clé : même contenu → la commande existante ; contenu différent → 409. */
    @Transactional
    public Creation replay(CustomerOrder existing, String requestHash, UUID userId) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, KEY_REUSED);
        }
        CustomerOrder order = orderRepository.findByIdForUpdate(existing.getId()).orElseThrow();
        String guestToken = null;
        if (order.getUserId() == null) {
            // Le jeton n'est stocké que hashé : un nouveau jeton remplace l'ancien (perdu lors du timeout).
            guestToken = Tokens.random(32);
            order.setGuestTokenHash(Tokens.sha256(guestToken));
            order.setGuestTokenExpiresAt(Instant.now().plus(GUEST_TOKEN_TTL));
        }
        return new Creation(OrderMapper.toResponse(order, guestToken), false);
    }

    /** Rejoue après une violation d'unicité (deux requêtes simultanées avec la même clé). */
    @Transactional
    public Creation replayByKey(CreateOrderRequest req, String idempotencyKey, UUID userId) {
        CustomerOrder existing = orderRepository.findByIdempotencyKey(validKey(idempotencyKey))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, KEY_REUSED));
        return replay(existing, requestHash(req, userId), userId);
    }

    // ------------------------------------------------------------------ suivi client
    @Transactional(readOnly = true)
    public OrderResponse get(String number, OrderAccess access) {
        CustomerOrder order = load(number);
        access.check(order);
        return OrderMapper.toResponse(order, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> myOrders(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(orderRepository.findByUserId(userId, pageable).map(o -> OrderMapper.toResponse(o, null)));
    }

    @Transactional
    public OrderResponse cancel(String number, OrderAccess access, String reason) {
        CustomerOrder order = lock(number);
        access.check(order);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderMapper.toResponse(order, null);
        }
        if (!access.admin() && !order.getStatus().customerCanCancel()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cette commande ne peut plus être annulée (statut : " + order.getStatus().label().toLowerCase(Locale.ROOT) + ").");
        }
        lifecycle.cancel(order, reason, access.admin() ? "admin" : "client");
        return OrderMapper.toResponse(order, null);
    }

    // ------------------------------------------------------------------ administration
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderResponse> adminList(String q, String status, String paymentStatus,
                                                      String paymentMethod, int page, int size) {
        OrderStatus st = parseEnum(OrderStatus.class, status, "status");
        PaymentStatus ps = parseEnum(PaymentStatus.class, paymentStatus, "paymentStatus");
        PaymentMethod pm = parseEnum(PaymentMethod.class, paymentMethod, "paymentMethod");
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Specification<CustomerOrder> spec = (root, query, cb) -> {
            List<Predicate> ps2 = new ArrayList<>();
            ps2.add(cb.isNotNull(root.get("number")));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
                ps2.add(cb.or(
                        cb.like(cb.lower(root.<String>get("number")), like),
                        cb.like(cb.lower(root.<String>get("deliveryName")), like),
                        cb.like(cb.lower(root.<String>get("deliveryPhone")), like),
                        cb.like(cb.lower(root.<String>get("deliveryEmail")), like)));
            }
            if (st != null) ps2.add(cb.equal(root.get("status"), st));
            if (ps != null) ps2.add(cb.equal(root.get("paymentStatus"), ps));
            if (pm != null) ps2.add(cb.equal(root.get("paymentMethod"), pm));
            return cb.and(ps2.toArray(new Predicate[0]));
        };
        return PageResponse.from(orderRepository.findAll(spec, pageable)
                .map(o -> OrderMapper.toAdmin(o, List.of())));
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse adminGet(String number) {
        CustomerOrder order = load(number);
        return OrderMapper.toAdmin(order, paymentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()));
    }

    /** Transition logistique vérifiée ; ne change jamais le statut de paiement. */
    @Transactional
    public AdminOrderResponse adminUpdateStatus(String number, String status, String note, UUID adminId) {
        CustomerOrder order = lock(number);
        OrderStatus target = parseEnum(OrderStatus.class, status, "status");
        if (target == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Le statut est obligatoire.", Map.of("status", "Le statut est obligatoire."));
        }
        if (!order.getStatus().adminCanMoveTo(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "Transition impossible : "
                    + order.getStatus().label() + " → " + target.label() + ".");
        }
        if (target == OrderStatus.CANCELLED) {
            lifecycle.cancel(order, note, "admin:" + adminId);
        } else {
            order.setStatus(target);
            lifecycle.event(order, target.label(), note, "admin:" + adminId);
        }
        orderRepository.flush();
        return adminGet(number);
    }

    /**
     * Encaissement d'une commande payable à la livraison ou au retrait, une fois remise.
     * Une seule fois : même clé → réponse identique ; autre clé → 409.
     */
    @Transactional
    public AdminOrderResponse collectPayment(String number, String method, String reference, String idempotencyKey,
                                             UUID adminId) {
        String key = validKey(idempotencyKey);
        CustomerOrder order = lock(number);
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            if (key.equals(order.getCollectIdempotencyKey())) {
                return adminGet(number);
            }
            throw new ApiException(HttpStatus.CONFLICT, "Cette commande est déjà encaissée.");
        }
        if (order.getPaymentMethod() != PaymentMethod.CASH_ON_DELIVERY) {
            throw new ApiException(HttpStatus.CONFLICT, "Seules les commandes payables à la livraison ou au retrait s'encaissent ici.");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ApiException(HttpStatus.CONFLICT, "La commande doit d'abord être livrée ou remise au client.");
        }
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!List.of("CASH", "ORANGE_MONEY", "MTN_MOMO").contains(m)) {
            String msg = "Moyen invalide (CASH, ORANGE_MONEY ou MTN_MOMO).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("method", msg));
        }
        order.recordCollection(adminId, m, blankToNull(reference), order.getTotal(), key);
        order.setPaymentStatus(PaymentStatus.PAID);
        lifecycle.countSale(order);
        lifecycle.event(order, "Paiement encaissé", m + " — " + order.getTotal() + " " + order.getCurrency()
                + (reference == null || reference.isBlank() ? "" : " — réf. " + reference.trim()), "admin:" + adminId);
        orderRepository.flush();
        return adminGet(number);
    }

    /** Remboursement effectué hors plateforme (Mobile Money, espèces) : à tracer par l'admin. */
    @Transactional
    public AdminOrderResponse markRefunded(String number, String note, UUID adminId) {
        CustomerOrder order = lock(number);
        if (order.getPaymentStatus() != PaymentStatus.REFUND_PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Aucun remboursement n'est en attente pour cette commande.");
        }
        order.setPaymentStatus(PaymentStatus.REFUNDED);
        lifecycle.event(order, "Remboursement effectué", note, "admin:" + adminId);
        orderRepository.flush();
        return adminGet(number);
    }

    // ------------------------------------------------------------------ helpers
    CustomerOrder load(String number) {
        return orderRepository.findByNumber(number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    CustomerOrder lock(String number) {
        return orderRepository.findByNumberForUpdate(number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    /** 409 avec le panier recalculé pour que le frontend l'actualise et redemande confirmation. */
    private CheckoutConflictException conflict(String message, String code, CreateOrderRequest req) {
        List<CartItemInput> items = req.items().stream()
                .map(i -> new CartItemInput(i.productId(), blankToNull(i.sku()), i.qty(), null)).toList();
        List<PricedLine> priced = inventory.price(items);
        List<QuoteIssue> issues = priced.stream().filter(p -> p.issue() != null)
                .map(p -> new QuoteIssue(p.line().productId(), p.line().sku(), p.issue().name(), p.line().message()))
                .toList();
        var lines = priced.stream().map(PricedLine::line).toList();
        return new CheckoutConflictException(new CheckoutConflictResponse(message, null, code, issues, lines,
                InventoryService.subtotal(lines)));
    }

    private String newNumber() {
        for (int i = 0; i < 10; i++) {
            String n = Tokens.reference("HD-", 8);
            if (!orderRepository.existsByNumber(n)) {
                return n;
            }
        }
        throw new IllegalStateException("Impossible de générer un numéro de commande unique");
    }

    private String requestHash(CreateOrderRequest req, UUID userId) {
        try {
            return Tokens.sha256((userId == null ? "guest" : userId.toString()) + "|" + json.writeValueAsString(req));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String validKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() < 8 || key.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, BAD_KEY);
        }
        return key.trim();
    }

    static PaymentMethod parseMethod(String value) {
        if (value == null) return null;
        try {
            return PaymentMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String msg = "Valeur invalide : " + value + ".";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
