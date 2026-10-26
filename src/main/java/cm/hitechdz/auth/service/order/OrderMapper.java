package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.Payment;
import cm.hitechdz.auth.dto.order.AdminOrderResponse;
import cm.hitechdz.auth.dto.order.DeliveryResponse;
import cm.hitechdz.auth.dto.order.OrderItemResponse;
import cm.hitechdz.auth.dto.order.OrderResponse;
import cm.hitechdz.auth.dto.order.PaymentStatusResponse;
import cm.hitechdz.auth.dto.order.TimelineEntry;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {}

    public static OrderResponse toResponse(CustomerOrder o, String guestAccessToken) {
        List<OrderItemResponse> items = o.getItems().stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getSku(), i.getName(), i.getVariantLabel(),
                        i.getImage(), i.getQty(), i.getUnitPrice(), i.getUnitPrice() * i.getQty()))
                .toList();
        DeliveryResponse delivery = new DeliveryResponse(o.getDeliveryName(), o.getDeliveryPhone(),
                o.getDeliveryEmail(), o.getDeliveryZoneId(), o.getDeliveryZoneLabel(), o.getDeliveryCity(),
                o.getDeliveryAddress(), o.getDeliveryNotes() == null ? "" : o.getDeliveryNotes());
        List<TimelineEntry> timeline = o.getEvents().stream()
                .map(e -> new TimelineEntry(e.getStatus().name(), e.getLabel(), e.getCreatedAt(), e.getNote()))
                .toList();
        return new OrderResponse(o.getNumber(), o.getCreatedAt(), o.getStatus().name(), o.getPaymentStatus().name(),
                o.getPaymentMethod() == null ? null : o.getPaymentMethod().name(), o.getCurrency(), o.getSubtotal(),
                o.getDiscount(), o.getShipping(), o.getTotal(), o.getPromoCode(), items, delivery, timeline,
                o.isStockReserved() ? o.getExpiresAt() : null, guestAccessToken);
    }

    public static AdminOrderResponse toAdmin(CustomerOrder o, List<Payment> payments) {
        AdminOrderResponse.Collected collected = o.getCollectedAt() == null ? null
                : new AdminOrderResponse.Collected(o.getCollectedAt(), o.getCollectedBy(), o.getCollectedMethod(),
                o.getCollectedReference(), o.getCollectedAmount());
        return new AdminOrderResponse(toResponse(o, null), o.getUserId(), o.getUserId() == null,
                o.isStockReserved(), collected, payments.stream().map(OrderMapper::toPaymentStatus).toList());
    }

    public static PaymentStatusResponse toPaymentStatus(Payment p) {
        return new PaymentStatusResponse(p.getId(), p.getOrder().getNumber(), p.getStatus().name(), p.getAmount(),
                p.getCurrency(), p.getProviderReference(), p.getMessage());
    }
}
