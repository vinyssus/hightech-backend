package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import cm.hitechdz.auth.domain.catalog.ProductVariant;
import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderItem;
import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.dto.catalog.CartItemInput;
import cm.hitechdz.auth.dto.catalog.CartLine;
import cm.hitechdz.auth.dto.catalog.CartValidateResponse;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ProductVariantRepository;
import cm.hitechdz.auth.repository.order.CustomerOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Impact des achats sur le catalogue : validation du panier (le prix du serveur fait foi),
 * réservation du stock à la création d'une commande, confirmation, remise en stock.
 * Le module Commandes (paiement Orange Money / MTN MoMo) appellera ces méthodes.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public static final int MAX_QTY_PER_ITEM = 10;
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(2);

    static final String UNAVAILABLE = "Produit indisponible";
    static final String PRICE_CHANGED = "Le prix a changé";
    static final String CHOOSE_OPTION = "Veuillez choisir une option";
    static final String MAX_QTY = "Quantité maximale : 10 par article";
    static final String INSUFFICIENT_STOCK = "Stock insuffisant pour certains articles.";
    static final String ORDER_NOT_FOUND = "Commande introuvable.";

    /** Article à réserver. */
    public record ReserveItem(String productId, String sku, int qty) {}

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CustomerOrderRepository orderRepository;
    private final TransactionTemplate tx;

    public InventoryService(ProductRepository productRepository, ProductVariantRepository variantRepository,
                            CustomerOrderRepository orderRepository, PlatformTransactionManager txManager) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.orderRepository = orderRepository;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ panier
    @Transactional(readOnly = true)
    public CartValidateResponse validateCart(List<CartItemInput> items) {
        List<CartLine> lines = new ArrayList<>();
        long subtotal = 0;
        for (CartItemInput in : items) {
            CartLine line = validateLine(in);
            lines.add(line);
            if (line.available()) {
                subtotal += line.unitPrice() * line.qty();
            }
        }
        return new CartValidateResponse(lines, subtotal);
    }

    private CartLine validateLine(CartItemInput in) {
        Optional<Product> found = productRepository.findById(in.productId());
        if (found.isEmpty() || !found.get().isPublic()) {
            return unavailable(in, found.map(Product::getName).orElse(null), UNAVAILABLE);
        }
        Product p = found.get();
        String image = CatalogMapper.mainImage(p);
        long unitPrice = p.getPrice();
        Long oldPrice = p.getOldPrice();
        int stock = p.getStock();
        String sku = null;
        String name = p.getName();

        if (p.hasVariants()) {
            ProductVariant v = in.sku() == null ? null : p.getVariants().stream()
                    .filter(x -> x.getSku().equals(in.sku())).findFirst().orElse(null);
            if (v == null) {
                return new CartLine(p.getId(), in.sku(), name, image, p.getPrice(), oldPrice, p.getStock(),
                        0, false, in.sku() == null ? CHOOSE_OPTION : UNAVAILABLE);
            }
            sku = v.getSku();
            unitPrice = p.getPrice() + v.getPriceDelta();
            oldPrice = oldPrice == null ? null : oldPrice + v.getPriceDelta();
            stock = v.getStock();
            String label = v.label();
            if (!label.isEmpty()) {
                name = name + " (" + label + ")";
            }
        }

        if (stock <= 0) {
            return new CartLine(p.getId(), sku, name, image, unitPrice, oldPrice, 0, 0, false, UNAVAILABLE);
        }
        int qty = in.qty();
        String message = null;
        if (qty > MAX_QTY_PER_ITEM) {
            qty = MAX_QTY_PER_ITEM;
            message = MAX_QTY;
        }
        if (qty > stock) {
            qty = stock;
            message = "Plus que " + stock + " en stock";
        }
        if (message == null && in.price() != null && in.price() != unitPrice) {
            message = PRICE_CHANGED;
        }
        return new CartLine(p.getId(), sku, name, image, unitPrice, oldPrice, stock, qty, true, message);
    }

    private static CartLine unavailable(CartItemInput in, String name, String message) {
        return new CartLine(in.productId(), in.sku(), name, null, 0, null, 0, 0, false, message);
    }

    // ------------------------------------------------------------------ réservation
    /**
     * Réserve le stock à la création d'une commande (paiement en attente, valable 2 minutes).
     * Verrou pessimiste (SELECT ... FOR UPDATE) dans un ordre fixe : deux achats simultanés de la
     * dernière unité ne peuvent pas réussir tous les deux. Stock insuffisant : 409 avec le détail.
     */
    @Transactional
    public CustomerOrder reserve(UUID userId, List<ReserveItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Le panier est vide.");
        }
        // Verrouillage dans l'ordre des identifiants pour éviter les interblocages
        Map<String, Product> locked = new TreeMap<>();
        items.stream().map(ReserveItem::productId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> productRepository.findByIdForUpdate(id).ifPresent(p -> locked.put(id, p)));
        Map<String, List<ProductVariant>> lockedVariants = new LinkedHashMap<>();
        for (Product p : locked.values()) {
            if (p.hasVariants()) {
                lockedVariants.put(p.getId(), variantRepository.findByProductIdForUpdate(p.getId()));
            }
        }

        Map<String, String> errors = new LinkedHashMap<>();
        CustomerOrder order = new CustomerOrder();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setExpiresAt(Instant.now().plus(RESERVATION_TTL));
        long subtotal = 0;

        for (int i = 0; i < items.size(); i++) {
            ReserveItem item = items.get(i);
            String key = "items[" + i + "]";
            Product p = locked.get(item.productId());
            if (p == null || !p.isPublic()) {
                errors.put(key, UNAVAILABLE);
                continue;
            }
            if (item.qty() < 1 || item.qty() > MAX_QTY_PER_ITEM) {
                errors.put(key, MAX_QTY);
                continue;
            }
            ProductVariant variant = null;
            if (p.hasVariants()) {
                variant = lockedVariants.get(p.getId()).stream()
                        .filter(v -> v.getSku().equals(item.sku())).findFirst().orElse(null);
                if (variant == null) {
                    errors.put(key, item.sku() == null ? CHOOSE_OPTION : UNAVAILABLE);
                    continue;
                }
            }
            int available = variant != null ? variant.getStock() : p.getStock();
            if (available < item.qty()) {
                errors.put(key, available == 0 ? UNAVAILABLE : "Plus que " + available + " en stock");
                continue;
            }
            long unitPrice = p.getPrice() + (variant != null ? variant.getPriceDelta() : 0);
            if (variant != null) {
                variant.setStock(variant.getStock() - item.qty());
                p.recomputeStockFromVariants();
            } else {
                p.setStock(p.getStock() - item.qty());
            }
            order.addItem(new OrderItem(p.getId(), variant == null ? null : variant.getSku(), p.getName(),
                    variant == null ? null : emptyToNull(variant.label()), CatalogMapper.mainImage(p),
                    unitPrice, item.qty()));
            subtotal += unitPrice * item.qty();
        }

        if (!errors.isEmpty()) {
            // Rollback de la transaction : aucune décrémentation n'est conservée
            throw new ApiException(HttpStatus.CONFLICT, INSUFFICIENT_STOCK, errors);
        }
        order.setSubtotal(subtotal);
        CustomerOrder saved = orderRepository.saveAndFlush(order);
        log.info("Stock réservé pour la commande {} ({} article(s))", saved.getId(), saved.getItems().size());
        return saved;
    }

    /** Paiement confirmé : la commande passe à PAID et les ventes (sold) sont incrémentées. */
    @Transactional
    public void confirmPayment(UUID orderId) {
        CustomerOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "La commande n'est plus en attente de paiement (statut : " + order.getStatus() + ").");
        }
        order.setStatus(OrderStatus.PAID);
        order.setExpiresAt(null);
        for (OrderItem item : order.getItems()) {
            productRepository.incrementSold(item.getProductId(), item.getQty());
        }
    }

    /** Paiement échoué : stock remis. */
    @Transactional
    public void failPayment(UUID orderId) {
        release(lockOrder(orderId), OrderStatus.FAILED);
    }

    /** Annulation (avant ou après paiement) : stock remis, ventes décomptées si la commande était payée. */
    @Transactional
    public void cancel(UUID orderId) {
        release(lockOrder(orderId), OrderStatus.CANCELLED);
    }

    /** Livraison : rend l'achat « vérifié » pour les avis. */
    @Transactional
    public void markDelivered(UUID orderId) {
        CustomerOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "Seule une commande payée peut être livrée.");
        }
        order.setStatus(OrderStatus.DELIVERED);
    }

    /** Libère les réservations expirées (paiement non reçu dans les 2 minutes). */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void releaseExpiredReservations() {
        List<UUID> expired = orderRepository.findExpiredIds(OrderStatus.PENDING_PAYMENT, Instant.now());
        for (UUID id : expired) {
            try {
                tx.executeWithoutResult(status -> {
                    CustomerOrder order = lockOrder(id);
                    if (order.getStatus() == OrderStatus.PENDING_PAYMENT
                            && order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
                        release(order, OrderStatus.EXPIRED);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Impossible de libérer la réservation {}", id, e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private void release(CustomerOrder order, OrderStatus finalStatus) {
        OrderStatus previous = order.getStatus();
        if (previous != OrderStatus.PENDING_PAYMENT && previous != OrderStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Le stock de cette commande a déjà été remis (statut : " + previous + ").");
        }
        List<OrderItem> items = new ArrayList<>(order.getItems());
        items.sort(Comparator.comparing(OrderItem::getProductId));
        for (OrderItem item : items) {
            Product p = productRepository.findByIdForUpdate(item.getProductId()).orElse(null);
            if (p == null) {
                continue;   // produit supprimé physiquement : rien à remettre
            }
            if (item.getSku() != null) {
                variantRepository.findByProductIdForUpdate(p.getId()).stream()
                        .filter(v -> v.getSku().equals(item.getSku()))
                        .findFirst()
                        .ifPresent(v -> v.setStock(v.getStock() + item.getQty()));
                p.recomputeStockFromVariants();
            } else {
                p.setStock(p.getStock() + item.getQty());
            }
            if (previous == OrderStatus.PAID) {
                productRepository.decrementSold(p.getId(), item.getQty());
            }
        }
        order.setStatus(finalStatus);
        order.setExpiresAt(null);
        log.info("Stock remis pour la commande {} ({})", order.getId(), finalStatus);
    }

    private CustomerOrder lockOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ORDER_NOT_FOUND));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
