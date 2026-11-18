package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.domain.checkout.ShippingZone;
import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.GuestAccessToken;
import cm.hitechdz.auth.domain.order.OrderSource;
import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.domain.order.PaymentMethod;
import cm.hitechdz.auth.domain.order.PaymentStatus;
import cm.hitechdz.auth.dto.order.AdminCreateOrderRequest;
import cm.hitechdz.auth.dto.order.AdminOrderResponse;
import cm.hitechdz.auth.dto.order.DeliveryInput;
import cm.hitechdz.auth.dto.order.OrderItemInput;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.UserRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.order.CustomerOrderRepository;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.InventoryService.ReserveItem;
import cm.hitechdz.auth.service.catalog.InventoryService.ReservedLine;
import cm.hitechdz.auth.util.PhoneUtil;
import cm.hitechdz.auth.util.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Commandes saisies par un administrateur après une discussion WhatsApp, et suivi d'une commande
 * par numéro + téléphone. Mêmes contrôles que le parcours client ; montants toujours recalculés.
 */
@Service
public class WhatsAppOrderService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOrderService.class);

    static final String LOOKUP_NOT_FOUND = "Commande introuvable. Vérifiez le numéro et le téléphone.";
    /** Erreurs de saisie (400) ; les autres erreurs de réservation sont des manques de stock (409). */
    private static final Set<String> INPUT_ERRORS = Set.of(InventoryService.CHOOSE_OPTION, InventoryService.INVALID_OPTION,
            InventoryService.MAX_QTY);

    private final CustomerOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryService inventory;
    private final ShopConfigService config;
    private final OrderLifecycle lifecycle;
    private final OrderService orders;

    public WhatsAppOrderService(CustomerOrderRepository orderRepository, ProductRepository productRepository,
                                UserRepository userRepository, InventoryService inventory, ShopConfigService config,
                                OrderLifecycle lifecycle, OrderService orders) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventory = inventory;
        this.config = config;
        this.lifecycle = lifecycle;
        this.orders = orders;
    }

    public record Creation(AdminOrderResponse order, boolean created) {}

    // ------------------------------------------------------------------ création par l'admin
    @Transactional
    public Creation create(AdminCreateOrderRequest req, String idempotencyKey, UUID adminId) {
        String key = "admin:" + OrderService.validKey(idempotencyKey);
        String requestHash = requestHash(req);
        Optional<CustomerOrder> existing = orderRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        // 1. Origine, coordonnées et mode de paiement (mêmes règles que /api/orders)
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.source() != null && !req.source().isBlank() && !"WHATSAPP".equalsIgnoreCase(req.source().trim())) {
            errors.put("source", "Origine invalide : seule WHATSAPP est acceptée ici.");
        }
        DeliveryInput d = req.delivery();
        ShippingZone zone = config.activeZone(d.zoneId(), "delivery.zoneId");
        String city = CheckoutService.requireCity(zone, d.city(), "delivery.city");
        String phone = PhoneUtil.normalizeCameroon(d.phone());
        if (phone == null) {
            errors.put("delivery.phone", "Numéro de téléphone camerounais invalide (ex. 6 77 12 34 56).");
        }
        if (!zone.isPickup() && (d.address() == null || d.address().isBlank())) {
            errors.put("delivery.address", "Indiquez l'adresse (quartier et repère).");
        }
        PaymentMethod method = OrderService.parseMethod(req.paymentMethod());
        if (method == null) {
            errors.put("paymentMethod", "Mode de paiement invalide (CASH_ON_DELIVERY, ORANGE_MONEY ou MTN_MOMO).");
        } else if (method == PaymentMethod.CASH_ON_DELIVERY && !zone.isCodAllowed()) {
            errors.put("paymentMethod", "Le paiement à la livraison n'est pas disponible pour cette zone.");
        }
        // Produits : existants et publiés
        List<OrderItemInput> items = req.items();
        for (int i = 0; i < items.size(); i++) {
            String productId = items.get(i).productId();
            boolean visible = productRepository.findById(productId).filter(p -> p.isPublic()).isPresent();
            if (!visible) {
                errors.put("items[" + i + "]", "Produit introuvable ou non publié.");
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Certains champs sont invalides.", errors);
        }

        // 2. Réservation verrouillée du stock et prix du serveur
        List<ReservedLine> reserved;
        try {
            reserved = inventory.reserve(items.stream()
                    .map(i -> new ReserveItem(i.productId(), blankToNull(i.sku()), i.qty())).toList());
        } catch (ApiException e) {
            Map<String, String> fe = e.getFieldErrors() == null ? Map.of() : e.getFieldErrors();
            boolean inputError = fe.values().stream().anyMatch(INPUT_ERRORS::contains);
            throw new ApiException(inputError ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT,
                    inputError ? "Certains articles sont invalides." : "Stock insuffisant pour certains articles.", fe);
        }
        long subtotal = reserved.stream().mapToLong(l -> l.unitPrice() * l.qty()).sum();
        long shipping = config.shippingFee(zone, subtotal, false);

        // 3. Commande confirmée, non payée
        CustomerOrder order = new CustomerOrder();
        order.setNumber(orders.newNumber());
        order.setSource(OrderSource.WHATSAPP);
        order.setCreatedByAdmin(adminId);
        order.setUserId(matchingCustomer(phone));
        order.setIdempotencyKey(key);
        order.setRequestHash(requestHash);
        reserved.forEach(l -> order.addItem(l.toOrderItem()));
        order.setSubtotal(subtotal);
        order.setDiscount(0);
        order.setShipping(shipping);
        order.setTotal(subtotal + shipping);
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
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        lifecycle.event(order, "Commande enregistrée via WhatsApp",
                order.getUserId() == null ? null : "Rattachée au compte client correspondant au téléphone.",
                "admin:" + adminId);
        orderRepository.saveAndFlush(order);
        log.info("Commande WhatsApp {} créée par l'admin {} ({} {}, {})", order.getNumber(), adminId,
                order.getTotal(), order.getCurrency(), method);
        return new Creation(orders.adminGet(order.getNumber()), true);
    }

    /** Même clé : même contenu → la commande existante ; contenu différent → 409. */
    @Transactional(readOnly = true)
    public Creation replay(CustomerOrder existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, OrderService.KEY_REUSED);
        }
        return new Creation(orders.adminGet(existing.getNumber()), false);
    }

    /** Après une violation d'unicité (deux envois simultanés avec la même clé). */
    @Transactional(readOnly = true)
    public Creation replayByKey(AdminCreateOrderRequest req, String idempotencyKey) {
        CustomerOrder existing = orderRepository.findByIdempotencyKey("admin:" + OrderService.validKey(idempotencyKey))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, OrderService.KEY_REUSED));
        return replay(existing, requestHash(req));
    }

    // ------------------------------------------------------------------ suivi sans compte
    /**
     * Numéro + téléphone de livraison → nouveau jeton de suivi (haché en base, 90 jours).
     * Réponse identique (404) que la commande n'existe pas ou que le téléphone ne corresponde pas.
     */
    @Transactional
    public String lookup(String number, String phone) {
        String normalizedPhone = PhoneUtil.normalizeCameroon(phone);
        String n = number == null ? "" : number.trim().toUpperCase(Locale.ROOT);
        CustomerOrder order = normalizedPhone == null ? null : orderRepository.findByNumberForUpdate(n).orElse(null);
        if (order == null || order.getDeliveryPhone() == null
                || !Tokens.constantTimeEquals(order.getDeliveryPhone(), normalizedPhone)) {
            throw new ApiException(HttpStatus.NOT_FOUND, LOOKUP_NOT_FOUND);
        }
        String token = Tokens.random(32);
        order.addExtraGuestToken(new GuestAccessToken(Tokens.sha256(token), Instant.now().plus(OrderService.GUEST_TOKEN_TTL)));
        orderRepository.flush();
        return token;
    }

    // ------------------------------------------------------------------ helpers
    /** Compte client actif au même numéro ; rattaché seulement s'il est unique (sinon commande invitée). */
    private UUID matchingCustomer(String phone) {
        if (phone == null) {
            return null;
        }
        String last9 = phone.substring(phone.length() - 9);
        List<User> candidates = userRepository.findActiveByPhoneDigits(Role.CUSTOMER, "%" + last9).stream()
                .filter(u -> phone.equals(PhoneUtil.normalizeCameroon(u.getPhone())))
                .toList();
        return candidates.size() == 1 ? candidates.get(0).getId() : null;
    }

    private static String requestHash(AdminCreateOrderRequest req) {
        StringBuilder sb = new StringBuilder("whatsapp|");
        for (OrderItemInput i : req.items()) {
            sb.append(i.productId()).append(',').append(blankToNull(i.sku())).append(',').append(i.qty()).append(';');
        }
        DeliveryInput d = req.delivery();
        sb.append('|').append(d.name()).append('|').append(d.phone()).append('|').append(blankToNull(d.email()))
                .append('|').append(d.zoneId()).append('|').append(blankToNull(d.city())).append('|')
                .append(blankToNull(d.address())).append('|').append(blankToNull(d.notes())).append('|')
                .append(req.paymentMethod());
        return Tokens.sha256(sb.toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
