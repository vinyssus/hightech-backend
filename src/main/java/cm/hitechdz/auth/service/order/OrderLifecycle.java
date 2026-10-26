package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderEvent;
import cm.hitechdz.auth.domain.order.OrderItem;
import cm.hitechdz.auth.domain.order.OrderStatus;
import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.domain.order.PaymentStatus;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.order.PaymentRepository;
import cm.hitechdz.auth.service.catalog.InventoryService;
import cm.hitechdz.auth.service.catalog.InventoryService.ReserveItem;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Règles d'état partagées : stock restitué une seule fois, ventes comptées une seule fois,
 * historique de chaque transition. Toujours appelé dans une transaction, commande verrouillée.
 */
@Component
public class OrderLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycle.class);

    private final InventoryService inventory;
    private final PromoService promos;
    private final PaymentRepository paymentRepository;

    public OrderLifecycle(InventoryService inventory, PromoService promos, PaymentRepository paymentRepository) {
        this.inventory = inventory;
        this.promos = promos;
        this.paymentRepository = paymentRepository;
    }

    public void event(CustomerOrder order, String label, String note, String actor) {
        order.addEvent(new OrderEvent(order.getStatus(), label, note, actor));
    }

    /** Restitue le stock réservé (une seule fois). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseStock(CustomerOrder order) {
        if (order.isStockReserved()) {
            inventory.restock(order.getItems());
            order.setStockReserved(false);
        }
    }

    /** Réserve de nouveau le stock d'une commande dont la réservation avait expiré (409 si impossible). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reReserve(CustomerOrder order, Duration ttl) {
        if (!order.isStockReserved()) {
            boolean ok = inventory.tryReserve(order.getItems().stream()
                    .map(i -> new ReserveItem(i.getProductId(), i.getSku(), i.getQty())).toList()).isPresent();
            if (!ok) {
                throw new ApiException(HttpStatus.CONFLICT, "Stock insuffisant pour certains articles.");
            }
            order.setStockReserved(true);
            event(order, "Stock réservé de nouveau", null, "système");
        }
        if (ttl != null) {
            Instant until = Instant.now().plus(ttl);
            if (order.getExpiresAt() == null || order.getExpiresAt().isBefore(until)) {
                order.setExpiresAt(until);
            }
        }
    }

    /** Ventes (sold) comptées une seule fois. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countSale(CustomerOrder order) {
        if (!order.isSoldCounted()) {
            inventory.addSold(order.getItems(), true);
            order.setSoldCounted(true);
        }
    }

    /**
     * Annulation : stock restitué si la marchandise n'est pas partie, ventes décomptées, code promo rendu,
     * paiements en cours abandonnés ; remboursement à effectuer si la commande était payée.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(CustomerOrder order, String note, String actor) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        if (order.getStatus().beforeShipping()) {
            releaseStock(order);
        }
        if (order.isSoldCounted()) {
            inventory.addSold(order.getItems(), false);
            order.setSoldCounted(false);
        }
        promos.release(order.getId());
        for (Payment p : paymentRepository.findPendingByOrder(order.getId())) {
            p.setStatus(Payment.Status.CANCELLED);
            p.setMessage("Commande annulée.");
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        } else if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.UNPAID);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setExpiresAt(null);
        event(order, OrderStatus.CANCELLED.label(), note, actor);
        if (order.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
            event(order, "Remboursement à effectuer", "La commande était payée.", "système");
        }
    }

    /**
     * Paiement confirmé par le prestataire. Si la réservation avait été libérée et que le stock n'est
     * plus disponible, la commande n'est pas servie : remboursement à effectuer (jamais de vente sans stock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentSucceeded(CustomerOrder order, Payment payment) {
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            log.warn("Paiement {} reçu alors que la commande {} est déjà payée", payment.getId(), order.getNumber());
            event(order, "Paiement en double reçu : remboursement à effectuer",
                    "Paiement " + payment.getId() + " (" + payment.getAmount() + " " + payment.getCurrency() + ")", "système");
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
            event(order, "Paiement reçu après annulation : remboursement à effectuer",
                    "Paiement " + payment.getId(), "système");
            return;
        }
        if (!order.isStockReserved()) {
            try {
                reReserve(order, null);
            } catch (ApiException e) {
                order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
                order.setStatus(OrderStatus.CANCELLED);
                order.setExpiresAt(null);
                promos.release(order.getId());
                event(order, "Paiement reçu après expiration de la réservation : stock indisponible",
                        "Remboursement à effectuer (paiement " + payment.getId() + ").", "système");
                return;
            }
        }
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setExpiresAt(null);
        countSale(order);
        for (Payment other : paymentRepository.findPendingByOrder(order.getId())) {
            if (!other.getId().equals(payment.getId())) {
                other.setStatus(Payment.Status.CANCELLED);
                other.setMessage("Un autre paiement a abouti.");
            }
        }
        event(order, "Paiement confirmé", operatorLabel(payment), "système");
    }

    /** Échec / expiration / annulation d'une tentative : la commande reste, le client peut réessayer. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentEnded(CustomerOrder order, Payment payment) {
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return;
        }
        boolean otherPending = paymentRepository.findPendingByOrder(order.getId()).stream()
                .anyMatch(p -> !p.getId().equals(payment.getId()));
        if (otherPending) {
            return;
        }
        order.setPaymentStatus(payment.getStatus() == Payment.Status.EXPIRED ? PaymentStatus.EXPIRED : PaymentStatus.FAILED);
        event(order, switch (payment.getStatus()) {
            case EXPIRED -> "Paiement expiré";
            case CANCELLED -> "Paiement annulé";
            default -> "Paiement échoué";
        }, payment.getMessage(), "système");
    }

    /** Fin de réservation d'une commande à payer en ligne : stock libéré, commande conservée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reservationExpired(CustomerOrder order) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.isStockReserved()) {
            return;
        }
        releaseStock(order);
        order.setExpiresAt(null);
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.EXPIRED);
        }
        event(order, "Réservation expirée : stock libéré",
                "Vous pouvez relancer le paiement tant que les articles sont disponibles.", "système");
    }

    static String operatorLabel(Payment p) {
        return switch (p.getOperator()) {
            case ORANGE_MONEY -> "Orange Money";
            case MTN_MOMO -> "MTN Mobile Money";
            default -> p.getOperator().name();
        } + (p.getProviderReference() == null ? "" : " — réf. " + p.getProviderReference());
    }

    static List<ReserveItem> toReserveItems(List<OrderItem> items) {
        return items.stream().map(i -> new ReserveItem(i.getProductId(), i.getSku(), i.getQty())).toList();
    }
}
