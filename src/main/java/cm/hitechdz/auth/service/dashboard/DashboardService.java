package cm.hitechdz.auth.service.dashboard;

import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.CategorySales;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.DayPoint;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.Kpis;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.LowStock;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.LowStockItem;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.Metric;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.PaymentShare;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.RecentOrder;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.StatusCount;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.Summary;
import cm.hitechdz.auth.dto.dashboard.DashboardResponse.TopProduct;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.service.catalog.CatalogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indicateurs du tableau de bord, calculés à chaque appel à partir des commandes enregistrées.
 * Définitions (affichées telles quelles dans l'admin) :
 * <ul>
 *   <li>commande valide : toute commande sauf CANCELLED, datée par sa création ;</li>
 *   <li>chiffre d'affaires : total (frais et remises inclus) des commandes valides ;</li>
 *   <li>ventes encaissées : total des commandes valides au statut de paiement PAID ;</li>
 *   <li>panier moyen : chiffre d'affaires / commandes valides ;</li>
 *   <li>taux de conversion : commandes valides / visites (un navigateur compté une fois par jour).</li>
 * </ul>
 */
@Service
public class DashboardService {

    public static final ZoneId ZONE = ZoneId.of("Africa/Douala");
    public static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    public static final int LOW_STOCK_THRESHOLD = 5;
    private static final int CATEGORY_LIMIT = 6;
    private static final int TOP_LIMIT = 5;
    private static final int RECENT_LIMIT = 8;
    private static final int LOW_STOCK_LIMIT = 8;

    private static final Set<String> PENDING = Set.of(OrderStatus.PENDING_PAYMENT.name(), OrderStatus.PENDING_CONFIRMATION.name());

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DashboardService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record Row(String number, String status, String paymentStatus, String paymentMethod, long total, Instant createdAt) {
        boolean valid() { return !OrderStatus.CANCELLED.name().equals(status); }
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(int days) {
        if (!PERIODS.contains(days)) {
            String msg = "Période invalide (7, 30 ou 90 jours).";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("days", msg));
        }
        Instant now = clock.instant();
        LocalDate to = LocalDate.ofInstant(now, ZONE);
        LocalDate from = to.minusDays(days - 1L);
        LocalDate prevFrom = from.minusDays(days);
        Instant start = startOf(from), end = startOf(to.plusDays(1)), prevStart = startOf(prevFrom);

        List<Row> rows = orders(prevStart, end);
        List<Row> cur = rows.stream().filter(r -> !r.createdAt().isBefore(start)).toList();
        List<Row> prev = rows.stream().filter(r -> r.createdAt().isBefore(start)).toList();
        List<Row> curValid = cur.stream().filter(Row::valid).toList();
        List<Row> prevValid = prev.stream().filter(Row::valid).toList();

        long revenue = sum(curValid), prevRevenue = sum(prevValid);
        long orders = curValid.size(), prevOrders = prevValid.size();
        Double basket = orders == 0 ? null : (double) Math.round((double) revenue / orders);
        Double prevBasket = prevOrders == 0 ? null : (double) Math.round((double) prevRevenue / prevOrders);
        long visits = visits(from, to), prevVisits = visits(prevFrom, from.minusDays(1));
        Double conversion = visits == 0 ? null : round2(100.0 * orders / visits);
        Double prevConversion = prevVisits == 0 ? null : round2(100.0 * prevOrders / prevVisits);

        Kpis kpis = new Kpis(
                metricPct((double) revenue, (double) prevRevenue),
                metricPct((double) orders, (double) prevOrders),
                metricPct(basket, prevBasket),
                new Metric(conversion, prevConversion,
                        conversion == null || prevConversion == null ? null : round2(conversion - prevConversion)),
                metricPct((double) visits, (double) prevVisits));

        Summary summary = new Summary(orders,
                curValid.stream().filter(r -> "PAID".equals(r.paymentStatus())).mapToLong(Row::total).sum(),
                cur.stream().filter(r -> PENDING.contains(r.status())).count(),
                cur.stream().filter(r -> OrderStatus.DELIVERED.name().equals(r.status())).count());

        ItemsAgg items = categoriesAndTop(start, end);
        return new DashboardResponse(days, from, to, "XAF", ZONE.getId(), now, summary, kpis,
                series(from, days, curValid, prevValid), payments(curValid), items.categories(),
                status(cur), lowStock(), items.top(), recent());
    }

    // ------------------------------------------------------------------ commandes
    private List<Row> orders(Instant from, Instant to) {
        return jdbc.query("""
                select number, status, payment_status, payment_method, total, created_at
                  from orders where created_at >= :from and created_at < :to
                """, range(from, to), (rs, i) -> new Row(rs.getString("number"), rs.getString("status"),
                rs.getString("payment_status"), rs.getString("payment_method"), rs.getLong("total"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    private List<DayPoint> series(LocalDate from, int days, List<Row> cur, List<Row> prev) {
        long[] revenue = new long[days], previous = new long[days], count = new long[days];
        LocalDate prevFrom = from.minusDays(days);
        for (Row r : cur) {
            int i = (int) ChronoUnit.DAYS.between(from, LocalDate.ofInstant(r.createdAt(), ZONE));
            if (i >= 0 && i < days) { revenue[i] += r.total(); count[i]++; }
        }
        for (Row r : prev) {
            int i = (int) ChronoUnit.DAYS.between(prevFrom, LocalDate.ofInstant(r.createdAt(), ZONE));
            if (i >= 0 && i < days) previous[i] += r.total();
        }
        List<DayPoint> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) out.add(new DayPoint(from.plusDays(i), revenue[i], previous[i], count[i]));
        return out;
    }

    private List<PaymentShare> payments(List<Row> valid) {
        Map<String, String[]> methods = new LinkedHashMap<>();
        methods.put("ORANGE_MONEY", new String[]{"orange", "Orange Money"});
        methods.put("MTN_MOMO", new String[]{"mtn", "MTN MoMo"});
        methods.put("CASH_ON_DELIVERY", new String[]{"cash", "Paiement à la livraison"});
        long total = sum(valid);
        List<PaymentShare> out = new ArrayList<>();
        methods.forEach((method, meta) -> {
            List<Row> l = valid.stream().filter(r -> method.equals(r.paymentMethod())).toList();
            long amount = sum(l);
            out.add(new PaymentShare(meta[0], method, meta[1], amount, l.size(), total == 0 ? 0 : round4((double) amount / total)));
        });
        return out;
    }

    private List<StatusCount> status(List<Row> all) {
        Map<String, Object[]> buckets = new LinkedHashMap<>();
        buckets.put("pending", new Object[]{"En attente", Set.of("PENDING_PAYMENT", "PENDING_CONFIRMATION")});
        buckets.put("preparing", new Object[]{"Confirmées / en préparation", Set.of("CONFIRMED", "PREPARING")});
        buckets.put("shipping", new Object[]{"En livraison", Set.of("SHIPPED", "OUT_FOR_DELIVERY")});
        buckets.put("delivered", new Object[]{"Livrées", Set.of("DELIVERED")});
        buckets.put("cancelled", new Object[]{"Annulées", Set.of("CANCELLED")});
        List<StatusCount> out = new ArrayList<>();
        buckets.forEach((key, meta) -> {
            @SuppressWarnings("unchecked") Set<String> statuses = (Set<String>) meta[1];
            long n = all.stream().filter(r -> statuses.contains(r.status())).count();
            out.add(new StatusCount(key, (String) meta[0], n, all.isEmpty() ? 0 : round4((double) n / all.size())));
        });
        return out;
    }

    static String statusKey(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING_PAYMENT", "PENDING_CONFIRMATION" -> "pending";
            case "CONFIRMED", "PREPARING" -> "preparing";
            case "SHIPPED", "OUT_FOR_DELIVERY" -> "shipping";
            case "DELIVERED" -> "delivered";
            case "CANCELLED" -> "cancelled";
            default -> "pending";
        };
    }

    // ------------------------------------------------------------------ produits
    private record ItemsAgg(List<CategorySales> categories, List<TopProduct> top) {}

    /** Lignes des commandes valides de la période : ventes par catégorie et meilleurs produits. */
    private ItemsAgg categoriesAndTop(Instant start, Instant end) {
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select oi.product_id, oi.name as item_name, oi.image as item_image, oi.unit_price, oi.qty,
                       c.name as category, p.name as product_name
                  from order_items oi
                  join orders o on o.id = oi.order_id
                  left join products p on p.id = oi.product_id
                  left join categories c on c.id = p.category_id
                 where o.created_at >= :from and o.created_at < :to and o.status <> 'CANCELLED'
                """, range(start, end));

        Map<String, long[]> byCategory = new HashMap<>();
        Map<String, long[]> byProduct = new LinkedHashMap<>();
        Map<String, String[]> productInfo = new HashMap<>();
        for (Map<String, Object> l : lines) {
            long qty = ((Number) l.get("qty")).longValue();
            long amount = ((Number) l.get("unit_price")).longValue() * qty;
            String cat = l.get("category") == null ? "Sans catégorie" : (String) l.get("category");
            long[] c = byCategory.computeIfAbsent(cat, k -> new long[2]);
            c[0] += amount; c[1] += qty;
            String pid = (String) l.get("product_id");
            long[] p = byProduct.computeIfAbsent(pid, k -> new long[2]);
            p[0] += qty; p[1] += amount;
            productInfo.putIfAbsent(pid, new String[]{
                    l.get("product_name") != null ? (String) l.get("product_name") : (String) l.get("item_name"),
                    (String) l.get("item_image")});
        }

        List<CategorySales> categories = new ArrayList<>(byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> -v[0])).thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new CategorySales(e.getKey(), e.getValue()[0], e.getValue()[1])).toList());
        if (categories.size() > CATEGORY_LIMIT) {
            List<CategorySales> rest = categories.subList(CATEGORY_LIMIT - 1, categories.size());
            CategorySales others = new CategorySales("Autres", rest.stream().mapToLong(CategorySales::amount).sum(),
                    rest.stream().mapToLong(CategorySales::qty).sum());
            categories = new ArrayList<>(categories.subList(0, CATEGORY_LIMIT - 1));
            categories.add(others);
        }

        List<String> topIds = byProduct.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> -e.getValue()[0])
                        .thenComparingLong(e -> -e.getValue()[1]).thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT).map(Map.Entry::getKey).toList();
        Map<String, String> images = firstImages(topIds);
        List<TopProduct> top = topIds.stream().map(id -> {
            String[] info = productInfo.get(id);
            String image = images.getOrDefault(id, info[1]);
            return new TopProduct(id, info[0], CatalogMapper.deliveryUrl(image), byProduct.get(id)[0], byProduct.get(id)[1]);
        }).toList();

        return new ItemsAgg(List.copyOf(categories), top);
    }

    private Map<String, String> firstImages(List<String> productIds) {
        Map<String, String> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        jdbc.query("select product_id, url from product_images where product_id in (:ids) order by product_id, position",
                new MapSqlParameterSource("ids", productIds),
                rs -> { out.putIfAbsent(rs.getString("product_id"), rs.getString("url")); });
        return out;
    }

    private LowStock lowStock() {
        List<LowStockItem> rows = jdbc.query("""
                select id, name, stock from products
                 where deleted_at is null and status = 'PUBLISHED' and stock <= :threshold
                 order by stock asc, name asc
                 limit :limit
                """, new MapSqlParameterSource("threshold", LOW_STOCK_THRESHOLD).addValue("limit", LOW_STOCK_LIMIT),
                (rs, i) -> new LowStockItem(rs.getString("id"), rs.getString("name"), null, rs.getInt("stock")));
        Map<String, String> images = firstImages(rows.stream().map(LowStockItem::id).toList());
        return new LowStock(LOW_STOCK_THRESHOLD, rows.stream()
                .map(r -> new LowStockItem(r.id(), r.name(), CatalogMapper.deliveryUrl(images.get(r.id())), r.stock()))
                .toList());
    }

    // ------------------------------------------------------------------ dernières commandes
    private List<RecentOrder> recent() {
        return jdbc.query("""
                select number, created_at, delivery_name, delivery_city, delivery_zone_label, payment_method,
                       payment_status, status, total, source
                  from orders where number is not null
                 order by created_at desc
                 limit :limit
                """, new MapSqlParameterSource("limit", RECENT_LIMIT), (rs, i) -> {
            String status = rs.getString("status");
            String method = rs.getString("payment_method");
            String city = rs.getString("delivery_city") != null ? rs.getString("delivery_city") : rs.getString("delivery_zone_label");
            return new RecentOrder(rs.getString("number"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getString("delivery_name"), city, method, paymentLabel(method), rs.getString("payment_status"),
                    status, statusKey(status), statusLabel(status), rs.getLong("total"), rs.getString("source"));
        });
    }

    static String paymentLabel(String method) {
        if (method == null) return "—";
        return switch (method) {
            case "ORANGE_MONEY" -> "Orange Money";
            case "MTN_MOMO" -> "MTN MoMo";
            case "CASH_ON_DELIVERY" -> "À la livraison";
            default -> method;
        };
    }

    private static String statusLabel(String status) {
        try {
            return OrderStatus.valueOf(status).label();
        } catch (RuntimeException e) {
            return status;
        }
    }

    // ------------------------------------------------------------------ visites
    private long visits(LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject("select count(*) from site_visits where visit_day >= :from and visit_day <= :to",
                new MapSqlParameterSource("from", java.sql.Date.valueOf(from)).addValue("to", java.sql.Date.valueOf(to)), Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ outils
    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static MapSqlParameterSource range(Instant from, Instant to) {
        return new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
    }

    private static long sum(List<Row> rows) {
        return rows.stream().mapToLong(Row::total).sum();
    }

    /** Variation en % ; null si aucune valeur de comparaison. */
    private static Metric metricPct(Double value, Double previous) {
        Double delta = value == null || previous == null || previous == 0 ? null : round2((value - previous) / previous * 100);
        return new Metric(value, previous, delta);
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000) / 10_000.0; }
}
