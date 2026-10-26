package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductVariant;
import cm.hitechdz.auth.domain.order.OrderItem;
import cm.hitechdz.auth.dto.catalog.CartItemInput;
import cm.hitechdz.auth.dto.catalog.CartLine;
import cm.hitechdz.auth.dto.catalog.CartValidateResponse;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ProductVariantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Stock et prix des articles : validation du panier (le prix du serveur fait foi), réservation
 * verrouillée, restitution et comptage des ventes. Le cycle de vie des commandes est géré par OrderService.
 */
@Service
public class InventoryService {

    public static final int MAX_QTY_PER_ITEM = 10;

    public static final String UNAVAILABLE = "Produit indisponible";
    public static final String PRICE_CHANGED = "Le prix a changé";
    public static final String CHOOSE_OPTION = "Veuillez choisir une option";
    public static final String INVALID_OPTION = "Cette option n'existe pas pour ce produit";
    public static final String MAX_QTY = "Quantité maximale : 10 par article";
    static final String INSUFFICIENT_STOCK = "Stock insuffisant pour certains articles.";

    /** Codes d'anomalie exposés par le devis. */
    public enum IssueCode { PRICE_CHANGED, OUT_OF_STOCK, QUANTITY_EXCEEDED, INVALID_VARIANT }

    /** Ligne de panier recalculée + anomalie éventuelle. */
    public record PricedLine(CartLine line, IssueCode issue) {}

    /** Article à réserver. */
    public record ReserveItem(String productId, String sku, int qty) {}

    /** Article réservé : copie figée pour la ligne de commande. */
    public record ReservedLine(String productId, String sku, String name, String variantLabel, String image,
                               long unitPrice, int qty) {
        public OrderItem toOrderItem() {
            return new OrderItem(productId, sku, name, variantLabel, image, unitPrice, qty);
        }
    }

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    public InventoryService(ProductRepository productRepository, ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    // ------------------------------------------------------------------ panier
    @Transactional(readOnly = true)
    public CartValidateResponse validateCart(List<CartItemInput> items) {
        List<PricedLine> priced = price(items);
        List<CartLine> lines = priced.stream().map(PricedLine::line).toList();
        return new CartValidateResponse(lines, subtotal(lines));
    }

    /**
     * Recalcule chaque ligne avec les données du serveur. Une ligne par couple produit/SKU
     * (les doublons sont fusionnés) ; un article indisponible reste présent avec available=false.
     */
    @Transactional(readOnly = true)
    public List<PricedLine> price(List<CartItemInput> items) {
        List<PricedLine> result = new ArrayList<>();
        for (CartItemInput in : merge(items)) {
            result.add(priceLine(in));
        }
        return result;
    }

    public static long subtotal(List<CartLine> lines) {
        return lines.stream().filter(CartLine::available).mapToLong(l -> l.unitPrice() * l.qty()).sum();
    }

    private static List<CartItemInput> merge(List<CartItemInput> items) {
        Map<String, CartItemInput> merged = new LinkedHashMap<>();
        for (CartItemInput in : items) {
            String key = in.productId() + "|" + (in.sku() == null ? "" : in.sku());
            merged.merge(key, in, (a, b) -> new CartItemInput(a.productId(), a.sku(), a.qty() + b.qty(),
                    a.price() != null ? a.price() : b.price()));
        }
        return new ArrayList<>(merged.values());
    }

    private PricedLine priceLine(CartItemInput in) {
        Optional<Product> found = productRepository.findById(in.productId());
        if (found.isEmpty() || !found.get().isPublic()) {
            return new PricedLine(new CartLine(in.productId(), in.sku(), found.map(Product::getName).orElse(null),
                    null, 0, null, 0, 0, false, UNAVAILABLE), IssueCode.OUT_OF_STOCK);
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
                return new PricedLine(new CartLine(p.getId(), in.sku(), name, image, p.getPrice(), oldPrice,
                        p.getStock(), 0, false, in.sku() == null ? CHOOSE_OPTION : INVALID_OPTION),
                        IssueCode.INVALID_VARIANT);
            }
            sku = v.getSku();
            unitPrice = p.getPrice() + v.getPriceDelta();
            oldPrice = oldPrice == null ? null : oldPrice + v.getPriceDelta();
            stock = v.getStock();
            String label = v.label();
            if (!label.isEmpty()) {
                name = name + " (" + label + ")";
            }
        } else if (in.sku() != null && !in.sku().isBlank()) {
            return new PricedLine(new CartLine(p.getId(), in.sku(), name, image, unitPrice, oldPrice, stock,
                    0, false, INVALID_OPTION), IssueCode.INVALID_VARIANT);
        }

        if (stock <= 0) {
            return new PricedLine(new CartLine(p.getId(), sku, name, image, unitPrice, oldPrice, 0, 0, false,
                    UNAVAILABLE), IssueCode.OUT_OF_STOCK);
        }
        int qty = in.qty();
        String message = null;
        IssueCode issue = null;
        if (qty > MAX_QTY_PER_ITEM) {
            qty = MAX_QTY_PER_ITEM;
            message = MAX_QTY;
            issue = IssueCode.QUANTITY_EXCEEDED;
        }
        if (qty > stock) {
            qty = stock;
            message = "Plus que " + stock + " en stock";
            issue = IssueCode.QUANTITY_EXCEEDED;
        }
        if (issue == null && in.price() != null && in.price() != unitPrice) {
            message = PRICE_CHANGED;
            issue = IssueCode.PRICE_CHANGED;
        }
        return new PricedLine(new CartLine(p.getId(), sku, name, image, unitPrice, oldPrice, stock, qty, true,
                message), issue);
    }

    // ------------------------------------------------------------------ réservation
    /**
     * Décrémente le stock dans la transaction en cours (obligatoire). Verrou pessimiste
     * (SELECT ... FOR UPDATE) dans un ordre fixe : deux achats simultanés de la dernière unité
     * ne peuvent pas réussir tous les deux. Stock insuffisant : 409 avec le détail par article
     * et rien n'est décrémenté (annulation de la transaction).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReservedLine> reserve(List<ReserveItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Le panier est vide.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<ReservedLine> reserved = doReserve(items, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, INSUFFICIENT_STOCK, errors);
        }
        return reserved;
    }

    /**
     * Comme reserve, mais sans exception (la transaction appelante n'est donc pas marquée en échec) :
     * renvoie vide si un article n'est plus disponible, sans rien décrémenter.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<List<ReservedLine>> tryReserve(List<ReserveItem> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<ReservedLine> reserved = doReserve(items, errors);
        return errors.isEmpty() ? Optional.of(reserved) : Optional.empty();
    }

    /** Vérifie tous les articles sous verrou, puis décrémente seulement si tout est disponible. */
    private List<ReservedLine> doReserve(List<ReserveItem> items, Map<String, String> errors) {
        Map<String, Product> locked = lockProducts(items.stream().map(ReserveItem::productId).toList());
        Map<String, List<ProductVariant>> lockedVariants = new LinkedHashMap<>();
        for (Product p : locked.values()) {
            if (p.hasVariants()) {
                lockedVariants.put(p.getId(), variantRepository.findByProductIdForUpdate(p.getId()));
            }
        }

        record Planned(Product product, ProductVariant variant, ReserveItem item) {}
        List<Planned> plan = new ArrayList<>();
        Map<String, Integer> demand = new LinkedHashMap<>();
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
                    errors.put(key, item.sku() == null ? CHOOSE_OPTION : INVALID_OPTION);
                    continue;
                }
            } else if (item.sku() != null && !item.sku().isBlank()) {
                errors.put(key, INVALID_OPTION);
                continue;
            }
            String stockKey = p.getId() + "|" + (variant == null ? "" : variant.getSku());
            int wanted = demand.merge(stockKey, item.qty(), Integer::sum);
            int available = variant != null ? variant.getStock() : p.getStock();
            if (available < wanted) {
                int left = Math.max(available - (wanted - item.qty()), 0);
                errors.put(key, left == 0 ? UNAVAILABLE : "Plus que " + left + " en stock");
                continue;
            }
            plan.add(new Planned(p, variant, item));
        }
        if (!errors.isEmpty()) {
            return List.of();
        }

        List<ReservedLine> reserved = new ArrayList<>();
        for (Planned pl : plan) {
            Product p = pl.product();
            ProductVariant variant = pl.variant();
            int qty = pl.item().qty();
            long unitPrice = p.getPrice() + (variant != null ? variant.getPriceDelta() : 0);
            if (variant != null) {
                variant.setStock(variant.getStock() - qty);
                p.recomputeStockFromVariants();
            } else {
                p.setStock(p.getStock() - qty);
            }
            String label = variant == null || variant.label().isEmpty() ? null : variant.label();
            reserved.add(new ReservedLine(p.getId(), variant == null ? null : variant.getSku(), p.getName(),
                    label, CatalogMapper.mainImage(p), unitPrice, qty));
        }
        productRepository.flush();
        return reserved;
    }

    /** Remet en stock les articles d'une commande (à appeler une seule fois : voir CustomerOrder.stockReserved). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(List<OrderItem> items) {
        List<OrderItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(OrderItem::getProductId));
        Map<String, Product> locked = lockProducts(sorted.stream().map(OrderItem::getProductId).toList());
        for (OrderItem item : sorted) {
            Product p = locked.get(item.getProductId());
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
        }
        productRepository.flush();
    }

    /** Ventes (sold) : +qty à la confirmation du paiement, -qty si une vente comptée est annulée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addSold(List<OrderItem> items, boolean increment) {
        for (OrderItem item : items) {
            if (increment) {
                productRepository.incrementSold(item.getProductId(), item.getQty());
            } else {
                productRepository.decrementSold(item.getProductId(), item.getQty());
            }
        }
    }

    private Map<String, Product> lockProducts(List<String> ids) {
        Map<String, Product> locked = new TreeMap<>();
        ids.stream().filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> productRepository.findByIdForUpdate(id).ifPresent(p -> locked.put(id, p)));
        return locked;
    }
}
