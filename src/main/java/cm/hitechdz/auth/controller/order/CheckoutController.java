package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.dto.order.CheckoutOptionsResponse;
import cm.hitechdz.auth.dto.order.QuoteRequest;
import cm.hitechdz.auth.dto.order.QuoteResponse;
import cm.hitechdz.auth.security.RateLimiter;
import cm.hitechdz.auth.service.order.CheckoutService;
import cm.hitechdz.auth.service.order.OrderAccess;
import cm.hitechdz.auth.service.order.ShopConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Options de livraison/paiement et devis (publics, panier invité compris). */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final ShopConfigService config;
    private final CheckoutService checkout;
    private final RateLimiter rateLimiter;

    public CheckoutController(ShopConfigService config, CheckoutService checkout, RateLimiter rateLimiter) {
        this.config = config;
        this.checkout = checkout;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/options")
    public CheckoutOptionsResponse options() {
        return config.options();
    }

    @PostMapping("/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request, HttpServletRequest http) {
        Limits.check(rateLimiter, "quote|" + http.getRemoteAddr(), 60, Duration.ofMinutes(1));
        return checkout.quote(request, OrderAccess.currentUserId());
    }
}
