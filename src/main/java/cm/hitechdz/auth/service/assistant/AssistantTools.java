package cm.hitechdz.auth.service.assistant;

import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.dto.catalog.CatalogPage;
import cm.hitechdz.auth.dto.catalog.CategoryDto;
import cm.hitechdz.auth.dto.catalog.Combination;
import cm.hitechdz.auth.dto.catalog.ProductDetail;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.dto.order.CheckoutOptionsResponse;
import cm.hitechdz.auth.dto.order.OrderResponse;
import cm.hitechdz.auth.dto.order.TimelineEntry;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.ProductFilter;
import cm.hitechdz.auth.service.catalog.CatalogQueryService;
import cm.hitechdz.auth.service.catalog.CategoryService;
import cm.hitechdz.auth.service.order.OrderAccess;
import cm.hitechdz.auth.service.order.OrderService;
import cm.hitechdz.auth.service.order.ShopConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Outils mis à disposition du modèle : lecture seule, uniquement les données réelles de la boutique.
 * Aucun outil ne crée de commande, ne modifie un prix ni ne révèle les codes promo.
 */
@Component
public class AssistantTools {

    /** Résultat d'un outil : texte JSON pour le modèle, produits à afficher, éventuel passage à WhatsApp. */
    public record Outcome(String result, List<ProductSummary> products, String handoffReason, String handoffMessage,
                          boolean error) {
        static Outcome of(String result) { return new Outcome(result, List.of(), null, null, false); }
        static Outcome error(String message) { return new Outcome(message, List.of(), null, null, true); }
    }

    /** Contexte d'accès aux commandes de la personne qui discute. */
    public record Caller(UUID userId, boolean admin, Map<String, String> guestTokens) {}

    static final Map<String, String> PAYMENT_STATUS = Map.of(
            "UNPAID", "Non payé", "PENDING", "Paiement en cours", "PAID", "Payé", "FAILED", "Échec du paiement",
            "EXPIRED", "Paiement expiré", "REFUND_PENDING", "Remboursement en cours", "REFUNDED", "Remboursé");
    static final Map<String, String> PAYMENT_METHOD = Map.of(
            "CASH_ON_DELIVERY", "Paiement à la livraison", "ORANGE_MONEY", "Orange Money", "MTN_MOMO", "MTN Mobile Money");

    private final CatalogQueryService catalog;
    private final CategoryService categories;
    private final ShopConfigService shopConfig;
    private final OrderService orders;
    private final ObjectMapper json;

    public AssistantTools(CatalogQueryService catalog, CategoryService categories, ShopConfigService shopConfig,
                          OrderService orders, ObjectMapper json) {
        this.catalog = catalog;
        this.categories = categories;
        this.shopConfig = shopConfig;
        this.orders = orders;
        this.json = json;
    }

    // ------------------------------------------------------------------ définitions
    public ArrayNode definitions() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("search_products",
                "Recherche des produits du catalogue HITECH DZ (prix, stock et marque réels). À utiliser pour toute "
                        + "question sur un produit, un prix, une disponibilité ou une recommandation.",
                props(
                        prop("query", "string", "Mots-clés (ex. « pc portable gamer », « iphone 15 », « écouteurs bluetooth »)."),
                        prop("category", "string", "Slug de catégorie facultatif (voir list_categories)."),
                        prop("subcategory", "string", "Slug de sous-catégorie facultatif, avec category."),
                        prop("max_price", "integer", "Prix maximum en FCFA, facultatif."),
                        prop("in_stock_only", "boolean", "true pour n'avoir que les produits en stock."),
                        prop("sort", "string", "pertinence, prix-asc, prix-desc, nouveautes ou popularite.")),
                List.of()));
        tools.add(tool("get_product",
                "Détails d'un produit : description, caractéristiques, options (couleur, capacité…) avec prix et stock.",
                props(prop("slug", "string", "Slug du produit, renvoyé par search_products.")),
                List.of("slug")));
        tools.add(tool("list_categories", "Liste des catégories et sous-catégories de la boutique.",
                props(), List.of()));
        tools.add(tool("get_delivery_and_payment_info",
                "Zones de livraison actives (frais, délais, paiement à la livraison) et moyens de paiement disponibles.",
                props(), List.of()));
        tools.add(tool("get_order_status",
                "Statut d'une commande (numéro au format HD-XXXXXXXX). Accessible seulement au client propriétaire "
                        + "(connecté ou sur l'appareil où la commande a été passée).",
                props(prop("order_number", "string", "Numéro de commande, ex. HD-7K2M9QXA.")),
                List.of("order_number")));
        tools.add(tool("contact_human",
                "Propose au client de continuer avec un conseiller sur WhatsApp : réclamation, retour, garantie, "
                        + "remboursement, négociation, demande que tu ne peux pas traiter, ou si le client le demande.",
                props(
                        prop("reason", "string", "Raison courte, ex. « Garantie d'un produit »."),
                        prop("summary", "string", "Message pré-rempli pour WhatsApp, écrit au nom du client, en français, "
                                + "2 phrases maximum, sans donnée personnelle (pas de téléphone ni d'adresse).")),
                List.of("reason", "summary")));
        return tools;
    }

    // ------------------------------------------------------------------ exécution
    public Outcome execute(String name, JsonNode input, Caller caller) {
        try {
            return switch (name) {
                case "search_products" -> searchProducts(input);
                case "get_product" -> getProduct(text(input, "slug"));
                case "list_categories" -> Outcome.of(write(listCategories()));
                case "get_delivery_and_payment_info" -> Outcome.of(write(deliveryInfo()));
                case "get_order_status" -> orderStatus(text(input, "order_number"), caller);
                case "contact_human" -> handoff(input);
                default -> Outcome.error("Outil inconnu : " + name);
            };
        } catch (ApiException e) {
            return Outcome.error(e.getMessage());
        }
    }

    private Outcome searchProducts(JsonNode in) {
        Long maxPrice = in.hasNonNull("max_price") && in.get("max_price").canConvertToLong()
                ? Math.max(0, in.get("max_price").asLong()) : null;
        String sort = text(in, "sort");
        if (sort != null && !List.of("pertinence", "prix-asc", "prix-desc", "nouveautes", "popularite").contains(sort)) {
            sort = null;
        }
        ProductFilter filter = CatalogQueryService.filter(text(in, "query"), text(in, "category"),
                text(in, "subcategory"), null, null, maxPrice, null, null,
                in.path("in_stock_only").asBoolean(false) ? Boolean.TRUE : null);
        CatalogPage page = catalog.search(filter, sort, 0, 6);
        List<ProductSummary> found = page.content();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductSummary p : found) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("slug", p.slug());
            r.put("name", p.name());
            r.put("brand", p.brand());
            r.put("category", p.category());
            r.put("subcategory", p.subcategory());
            r.put("price_fcfa", p.price());
            if (p.oldPrice() != null) r.put("old_price_fcfa", p.oldPrice());
            r.put("availability", p.stock() <= 0 ? "rupture de stock" : p.stock() <= 3 ? "plus que " + p.stock() : "en stock");
            if (p.reviews() > 0) r.put("rating", p.rating());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_matches", page.totalElements());
        out.put("products", rows);
        if (rows.isEmpty()) out.put("note", "Aucun produit trouvé. Essaie d'autres mots-clés ou propose WhatsApp.");
        return new Outcome(write(out), found, null, null, false);
    }

    private Outcome getProduct(String slug) {
        if (slug == null) {
            return Outcome.error("Le slug du produit est obligatoire.");
        }
        ProductDetail d = catalog.detail(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", d.slug());
        out.put("name", d.name());
        out.put("brand", d.brand());
        out.put("category", d.category());
        out.put("subcategory", d.subcategory());
        out.put("price_fcfa", d.price());
        if (d.oldPrice() != null) out.put("old_price_fcfa", d.oldPrice());
        out.put("availability", d.stock() <= 0 ? "rupture de stock" : "en stock");
        String desc = d.description() == null ? "" : d.description();
        out.put("description", desc.length() > 1500 ? desc.substring(0, 1500) + "…" : desc);
        out.put("specs", d.specs());
        List<Map<String, Object>> options = new ArrayList<>();
        if (d.combinations() != null) {
            for (Combination c : d.combinations()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("color", c.color());
                o.put("capacity", c.capacity());
                o.put("ram", c.ram());
                o.put("price_fcfa", c.price());
                o.put("availability", c.stock() <= 0 ? "rupture de stock" : "en stock");
                options.add(o);
            }
        }
        out.put("options", options);
        if (d.reviews() > 0) {
            out.put("rating", d.rating());
            out.put("reviews", d.reviews());
        }
        ProductSummary card = new ProductSummary(d.id(), d.slug(), d.name(), d.brand(), d.category(), d.subcategory(),
                d.price(), d.oldPrice(), d.rating(), d.reviews(), d.image(), d.stock(), d.isNew(), d.sold());
        return new Outcome(write(out), List.of(card), null, null, false);
    }

    private List<Map<String, Object>> listCategories() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CategoryDto c : categories.publicList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", c.slug());
            m.put("name", c.name());
            m.put("subcategories", c.subcategories());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> deliveryInfo() {
        CheckoutOptionsResponse o = shopConfig.options();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", o.currency());
        out.put("zones", o.shippingZones().stream().map(z -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", z.label());
            m.put("fee_fcfa", z.fee());
            m.put("delay", z.delay() == null ? "non précisé" : z.delay());
            m.put("cash_on_delivery", z.codAllowed());
            m.put("pickup_in_store", z.pickup());
            return m;
        }).toList());
        out.put("free_shipping_from_fcfa", o.freeShippingFrom());
        out.put("payment_methods", o.paymentMethods().stream().map(m -> PAYMENT_METHOD.getOrDefault(m, m)).toList());
        if (o.shippingZones().isEmpty()) {
            out.put("note", "La commande en ligne n'est pas encore ouverte : oriente le client vers WhatsApp.");
        }
        return out;
    }

    private Outcome orderStatus(String number, Caller caller) {
        if (number == null) {
            return Outcome.error("Le numéro de commande est obligatoire (format HD-XXXXXXXX).");
        }
        String n = number.trim().toUpperCase(Locale.ROOT);
        OrderAccess access = new OrderAccess(caller.userId(), caller.admin(), caller.guestTokens().get(n));
        OrderResponse o;
        try {
            o = orders.get(n, access);
        } catch (ApiException e) {
            int status = e.getStatus().value();
            if (status == 404) return Outcome.error("Commande introuvable. Vérifie le numéro (format HD-XXXXXXXX).");
            return Outcome.error("Accès refusé : pour voir cette commande, le client doit être connecté au compte qui "
                    + "l'a passée, ou utiliser l'appareil sur lequel il a commandé. Ne donne aucune information sur elle.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", o.number());
        out.put("created_at", o.createdAt());
        out.put("status", label(o.status()));
        out.put("payment_status", PAYMENT_STATUS.getOrDefault(o.paymentStatus(), o.paymentStatus()));
        out.put("payment_method", PAYMENT_METHOD.getOrDefault(o.paymentMethod(), o.paymentMethod()));
        out.put("total_fcfa", o.total());
        out.put("items", o.items().stream().map(i -> i.qty() + " × " + i.name()
                + (i.variantLabel() == null ? "" : " (" + i.variantLabel() + ")")).toList());
        out.put("delivery_zone", o.delivery() == null ? null : o.delivery().zoneLabel());
        List<TimelineEntry> t = o.timeline();
        out.put("history", t.subList(Math.max(0, t.size() - 5), t.size()).stream()
                .map(e -> e.at() + " : " + e.label()).toList());
        return Outcome.of(write(out));
    }

    private Outcome handoff(JsonNode in) {
        String reason = clip(text(in, "reason"), 120);
        String summary = clip(text(in, "summary"), 300);
        String message = "Bonjour HITECH DZ, " + (summary == null ? "j'ai une question." : summary);
        return new Outcome("{\"ok\":true,\"note\":\"Un bouton WhatsApp est proposé au client sous ta réponse.\"}",
                List.of(), reason == null ? "Besoin d'un conseiller" : reason, message, false);
    }

    // ------------------------------------------------------------------ helpers
    private static String label(String status) {
        try {
            return OrderStatus.valueOf(status).label();
        } catch (Exception e) {
            return status;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String clean = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String text(JsonNode in, String field) {
        JsonNode v = in == null ? null : in.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ObjectNode tool(String name, String description, ObjectNode properties, List<String> required) {
        ObjectNode t = json.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = schema.putArray("required");
        required.forEach(req::add);
        return t;
    }

    private ObjectNode props(ObjectNode... entries) {
        ObjectNode p = json.createObjectNode();
        for (ObjectNode e : entries) {
            p.setAll(e);
        }
        return p;
    }

    private ObjectNode prop(String name, String type, String description) {
        ObjectNode wrapper = json.createObjectNode();
        ObjectNode p = wrapper.putObject(name);
        p.put("type", type);
        p.put("description", description);
        return wrapper;
    }
}
