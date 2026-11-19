package cm.hitechdz.auth.dto.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Tableau de bord admin, calculé à partir des commandes réelles (fuseau Africa/Douala).
 * Commandes « valides » = toutes sauf CANCELLED. Montants en FCFA entiers.
 * Les variations (delta) valent null quand la période précédente est vide (pas de comparaison possible).
 */
public record DashboardResponse(
        int days, LocalDate from, LocalDate to, String currency, String timezone, Instant generatedAt,
        Summary summary, Kpis kpis, List<DayPoint> series, List<PaymentShare> payments,
        List<CategorySales> categories, List<StatusCount> status, LowStock lowStock,
        List<TopProduct> top, List<RecentOrder> recent) {

    /** Bloc « Commandes » : commandes valides, ventes encaissées (PAID), en attente, livrées. */
    public record Summary(long orders, long collected, long pending, long delivered) {}

    /** delta : % pour revenue/orders/basket, points pour conversion. */
    public record Metric(Double value, Double previous, Double delta) {}

    public record Kpis(Metric revenue, Metric orders, Metric basket, Metric conversion, Metric visitors) {}

    public record DayPoint(LocalDate date, long revenue, long previous, long orders) {}

    public record PaymentShare(String key, String method, String label, long amount, long count, double share) {}

    public record CategorySales(String name, long amount, long qty) {}

    public record StatusCount(String key, String label, long count, double share) {}

    public record LowStock(int threshold, List<LowStockItem> items) {}

    public record LowStockItem(String id, String name, String image, int stock) {}

    public record TopProduct(String id, String name, String image, long sold, long revenue) {}

    public record RecentOrder(String number, Instant date, String customer, String city, String paymentMethod,
                              String payment, String paymentStatus, String status, String statusKey,
                              String statusLabel, long total, String source) {}
}
