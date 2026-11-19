package cm.hitechdz.auth.controller.dashboard;

import cm.hitechdz.auth.exception.RateLimitedException;
import cm.hitechdz.auth.security.RateLimiter;
import cm.hitechdz.auth.service.dashboard.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Compteur de visites anonyme (public) : POST /api/analytics/visit {"visitorId":"…"} → 204. */
@RestController
public class VisitController {

    public record VisitRequest(String visitorId) {}

    private final VisitService visits;
    private final RateLimiter rateLimiter;

    public VisitController(VisitService visits, RateLimiter rateLimiter) {
        this.visits = visits;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/analytics/visit")
    public ResponseEntity<Void> visit(@RequestBody(required = false) VisitRequest request, HttpServletRequest http) {
        long retryAfter = rateLimiter.tryAcquire("visit|" + http.getRemoteAddr(), 30, Duration.ofMinutes(10));
        if (retryAfter > 0) {
            throw new RateLimitedException("Trop de tentatives. Veuillez réessayer dans quelques minutes.", retryAfter);
        }
        visits.record(request == null ? null : request.visitorId());
        return ResponseEntity.noContent().build();
    }
}
