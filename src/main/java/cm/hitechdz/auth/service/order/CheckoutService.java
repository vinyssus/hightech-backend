package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.checkout.CheckoutQuote;
import cm.hitechdz.auth.domain.checkout.ShippingZone;
import cm.hitechdz.auth.dto.catalog.CartItemInput;
import cm.hitechdz.auth.dto.catalog.CartLine;
import cm.hitechdz.auth.dto.order.QuoteIssue;
import cm.hitechdz.auth.dto.order.QuoteRequest;
import cm.hitechdz.auth.dto.order.QuoteResponse;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.checkout.CheckoutQuoteRepository;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.InventoryService.PricedLine;
import cm.hitechdz.auth.util.Tokens;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Devis : prix, remise, frais de livraison et total calculés par le serveur. Ne réserve pas le stock. */
@Service
public class CheckoutService {

    private final InventoryService inventory;
    private final ShopConfigService config;
    private final PromoService promos;
    private final CheckoutQuoteRepository quoteRepository;

    public CheckoutService(InventoryService inventory, ShopConfigService config, PromoService promos,
                           CheckoutQuoteRepository quoteRepository) {
        this.inventory = inventory;
        this.config = config;
        this.promos = promos;
        this.quoteRepository = quoteRepository;
    }

    @Transactional
    public QuoteResponse quote(QuoteRequest req, UUID userId) {
        ShippingZone zone = config.activeZone(req.zoneId(), "zoneId");
        String city = requireCity(zone, req.city(), "city");

        List<PricedLine> priced = inventory.price(req.items());
        List<CartLine> lines = priced.stream().map(PricedLine::line).toList();
        List<QuoteIssue> issues = priced.stream()
                .filter(p -> p.issue() != null)
                .map(p -> new QuoteIssue(p.line().productId(), p.line().sku(), p.issue().name(),
                        p.line().message()))
                .toList();
        long subtotal = InventoryService.subtotal(lines);

        PromoService.Applied promo = promos.evaluate(req.promoCode(), subtotal,
                userId == null ? null : customerKeyForUser(userId));
        long discount = promo == null ? 0 : promo.discount();
        long shipping = config.shippingFee(zone, subtotal - discount, promo != null && promo.freeShipping());
        long total = subtotal - discount + shipping;
        boolean canOrder = issues.isEmpty() && !lines.isEmpty() && lines.stream().allMatch(CartLine::available);

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(config.settings().getQuoteValidityMinutes()));
        CheckoutQuote quote = new CheckoutQuote("qt_" + Tokens.random(24),
                requestHash(req.items().stream().map(i -> new Item(i.productId(), i.sku(), i.qty())).toList(),
                        zone.getId(), city, req.promoCode()),
                userId, zone.getId(), normalizedPromo(req.promoCode()), subtotal, discount, shipping, total, expiresAt);
        quoteRepository.save(quote);

        return new QuoteResponse(quote.getId(), expiresAt, "XAF", lines, subtotal, discount, shipping, total,
                promo == null ? null : promo.summary(), canOrder, issues);
    }

    /** Élément de panier pour l'empreinte (devis et commande doivent porter sur le même panier). */
    public record Item(String productId, String sku, int qty) {}

    /** Empreinte du panier (doublons fusionnés, ordre indifférent), de la zone, de la ville et du code. */
    public static String requestHash(List<Item> items, String zoneId, String city, String promoCode) {
        Map<String, Integer> merged = items.stream().collect(Collectors.toMap(
                i -> i.productId() + ":" + (i.sku() == null ? "" : i.sku()), Item::qty, Integer::sum));
        String cart = merged.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
        String normalizedCity = city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
        String promo = normalizedPromo(promoCode);
        return Tokens.sha256(cart + "|" + zoneId + "|" + normalizedCity + "|" + (promo == null ? "" : promo));
    }

    static String normalizedPromo(String code) {
        if (code == null || code.isBlank()) return null;
        return code.trim().toUpperCase(Locale.ROOT);
    }

    static String customerKeyForUser(UUID userId) {
        return "user:" + userId;
    }

    /** Ville obligatoire pour la zone « autres villes » ; sinon le libellé de la zone fait office de ville. */
    static String requireCity(ShippingZone zone, String city, String field) {
        if (zone.isRequiresCity() && (city == null || city.isBlank())) {
            String msg = "Indiquez votre ville.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
        }
        return city == null || city.isBlank() ? null : city.trim();
    }

    /** Ordre stable des lignes pour les messages. */
    static final Comparator<CartItemInput> BY_PRODUCT = Comparator.comparing(CartItemInput::productId);
}
