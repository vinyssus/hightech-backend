package cm.hitechdz.auth.controller.order;

import cm.hitechdz.auth.exception.RateLimitedException;
import cm.hitechdz.auth.security.RateLimiter;

import java.time.Duration;

/** Limites anti-abus (par adresse IP) : 429 + Retry-After. */
final class Limits {

    private Limits() {}

    static void check(RateLimiter limiter, String key, int max, Duration window) {
        long retryAfter = limiter.tryAcquire(key, max, window);
        if (retryAfter > 0) {
            throw new RateLimitedException("Trop de tentatives. Veuillez réessayer dans quelques minutes.", retryAfter);
        }
    }
}
