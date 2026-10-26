package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.order.CancelRequest;
import cm.hitechdz.auth.dto.order.CreateOrderRequest;
import cm.hitechdz.auth.dto.order.OrderResponse;
import cm.hitechdz.auth.dto.order.PaymentInitRequest;
import cm.hitechdz.auth.dto.order.PaymentInitResponse;
import cm.hitechdz.auth.dto.order.PaymentMethodChangeRequest;
import cm.hitechdz.auth.dto.order.PaymentStatusResponse;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.security.RateLimiter;
import cm.hitechdz.auth.service.order.OrderAccess;
import cm.hitechdz.auth.service.order.OrderService;
import cm.hitechdz.auth.service.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Commandes côté client (compte connecté ou invité muni de X-Guest-Order-Token).
 * Les montants et statuts envoyés par le navigateur ne sont jamais pris en compte.
 */
@RestController
public class OrderController {

    private final OrderService orders;
    private final PaymentService payments;
    private final RateLimiter rateLimiter;

    public OrderController(OrderService orders, PaymentService payments, RateLimiter rateLimiter) {
        this.orders = orders;
        this.payments = payments;
        this.rateLimiter = rateLimiter;
    }

    /** 201 à la création ; 201 aussi quand la même clé rejoue une commande existante (aucun doublon). */
    @PostMapping("/api/orders")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                HttpServletRequest http) {
        var userId = OrderAccess.currentUserId();
        OrderService.Creation result;
        try {
            if (key != null) {
                Limits.check(rateLimiter, "orders|" + http.getRemoteAddr(), userId == null ? 10 : 30, Duration.ofMinutes(10));
            }
            result = orders.create(request, key, userId);
        } catch (DataIntegrityViolationException race) {
            // Deux requêtes simultanées avec la même clé : la seconde renvoie la commande de la première
            result = orders.replayByKey(request, key, userId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.order());
    }

    @GetMapping("/api/orders/{number}")
    public OrderResponse get(@PathVariable String number,
                             @RequestHeader(name = OrderAccess.GUEST_HEADER, required = false) String guestToken) {
        return orders.get(number, OrderAccess.current(guestToken));
    }

    @PostMapping("/api/orders/{number}/cancel")
    public OrderResponse cancel(@PathVariable String number,
                                @RequestHeader(name = OrderAccess.GUEST_HEADER, required = false) String guestToken,
                                @Valid @RequestBody(required = false) CancelRequest request) {
        return orders.cancel(number, OrderAccess.current(guestToken), request == null ? null : request.reason());
    }

    @PatchMapping("/api/orders/{number}/payment-method")
    public OrderResponse changePaymentMethod(@PathVariable String number,
                                             @RequestHeader(name = OrderAccess.GUEST_HEADER, required = false) String guestToken,
                                             @Valid @RequestBody PaymentMethodChangeRequest request) {
        return payments.switchToCashOnDelivery(number, OrderAccess.current(guestToken), request.paymentMethod());
    }

    @PostMapping("/api/orders/{number}/payments")
    public ResponseEntity<PaymentInitResponse> pay(@PathVariable String number,
                                                   @RequestHeader(name = OrderAccess.GUEST_HEADER, required = false) String guestToken,
                                                   @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                   @Valid @RequestBody PaymentInitRequest request,
                                                   HttpServletRequest http) {
        Limits.check(rateLimiter, "payments|" + http.getRemoteAddr(), 10, Duration.ofMinutes(10));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(payments.initiate(number, OrderAccess.current(guestToken), key, request));
    }

    /** Statut d'un paiement ; Retry-After indique quand réinterroger tant qu'il est en attente. */
    @GetMapping("/api/orders/{number}/payments/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> paymentStatus(@PathVariable String number, @PathVariable String paymentId,
                                                               @RequestHeader(name = OrderAccess.GUEST_HEADER, required = false) String guestToken) {
        PaymentStatusResponse status = payments.status(number, paymentId, OrderAccess.current(guestToken));
        var response = ResponseEntity.ok();
        if ("PENDING".equals(status.status())) {
            response.header("Retry-After", String.valueOf(PaymentService.MIN_REFRESH_INTERVAL.toSeconds()));
        }
        return response.body(status);
    }

    @GetMapping("/api/me/orders")
    public PageResponse<OrderResponse> myOrders(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return orders.myOrders(user.id(), page, size);
    }
}
