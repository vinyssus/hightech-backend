package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.order.AdminOrderResponse;
import cm.hitechdz.auth.dto.order.CancelRequest;
import cm.hitechdz.auth.dto.order.CollectPaymentRequest;
import cm.hitechdz.auth.dto.order.PromoDto;
import cm.hitechdz.auth.dto.order.PromoRequest;
import cm.hitechdz.auth.dto.order.ShippingZoneDto;
import cm.hitechdz.auth.dto.order.ShippingZoneRequest;
import cm.hitechdz.auth.dto.order.ShopSettingsDto;
import cm.hitechdz.auth.dto.order.StatusUpdateRequest;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.service.order.OrderService;
import cm.hitechdz.auth.service.order.PromoService;
import cm.hitechdz.auth.service.order.ShopConfigService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administration des commandes, zones de livraison, réglages et codes promo (rôle ADMIN). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final OrderService orders;
    private final ShopConfigService config;
    private final PromoService promos;

    public AdminOrderController(OrderService orders, ShopConfigService config, PromoService promos) {
        this.orders = orders;
        this.config = config;
        this.promos = promos;
    }

    // ------------------------------------------------------------------ commandes
    @GetMapping("/orders")
    public PageResponse<AdminOrderResponse> list(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String paymentStatus,
                                                 @RequestParam(required = false) String paymentMethod,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        return orders.adminList(q, status, paymentStatus, paymentMethod, page, size);
    }

    @GetMapping("/orders/{number}")
    public AdminOrderResponse get(@PathVariable String number) {
        return orders.adminGet(number);
    }

    @PatchMapping("/orders/{number}/status")
    public AdminOrderResponse status(@PathVariable String number, @Valid @RequestBody StatusUpdateRequest request,
                                     @AuthenticationPrincipal AuthenticatedUser admin) {
        return orders.adminUpdateStatus(number, request.status(), request.note(), admin.id());
    }

    @PostMapping("/orders/{number}/collect-payment")
    public AdminOrderResponse collect(@PathVariable String number, @Valid @RequestBody CollectPaymentRequest request,
                                      @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                      @AuthenticationPrincipal AuthenticatedUser admin) {
        return orders.collectPayment(number, request.method(), request.reference(), key, admin.id());
    }

    @PostMapping("/orders/{number}/mark-refunded")
    public AdminOrderResponse markRefunded(@PathVariable String number,
                                           @Valid @RequestBody(required = false) CancelRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser admin) {
        return orders.markRefunded(number, request == null ? null : request.reason(), admin.id());
    }

    // ------------------------------------------------------------------ zones de livraison
    @GetMapping("/shipping-zones")
    public List<ShippingZoneDto> zones() {
        return config.adminZones();
    }

    @PostMapping("/shipping-zones")
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingZoneDto createZone(@Valid @RequestBody ShippingZoneRequest request) {
        return config.createZone(request);
    }

    @PutMapping("/shipping-zones/{id}")
    public ShippingZoneDto updateZone(@PathVariable String id, @Valid @RequestBody ShippingZoneRequest request) {
        return config.updateZone(id, request);
    }

    @DeleteMapping("/shipping-zones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteZone(@PathVariable String id) {
        config.deleteZone(id);
    }

    // ------------------------------------------------------------------ réglages
    @GetMapping("/checkout/settings")
    public ShopSettingsDto settings() {
        return config.adminSettings();
    }

    @PutMapping("/checkout/settings")
    public ShopSettingsDto updateSettings(@Valid @RequestBody ShopSettingsDto request) {
        return config.updateSettings(request);
    }

    // ------------------------------------------------------------------ codes promo
    @GetMapping("/promos")
    public List<PromoDto> promos() {
        return promos.adminList();
    }

    @PostMapping("/promos")
    @ResponseStatus(HttpStatus.CREATED)
    public PromoDto createPromo(@Valid @RequestBody PromoRequest request) {
        return promos.create(request);
    }

    @PutMapping("/promos/{code}")
    public PromoDto updatePromo(@PathVariable String code, @Valid @RequestBody PromoRequest request) {
        return promos.update(code, request);
    }

    @DeleteMapping("/promos/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePromo(@PathVariable String code) {
        promos.delete(code);
    }
}
