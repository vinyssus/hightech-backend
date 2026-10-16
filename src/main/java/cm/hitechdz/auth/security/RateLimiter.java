package cm.hitechdz.auth.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter en mémoire à fenêtre glissante (une instance).
 * Pour un déploiement multi-instances, remplacer par Redis / Bucket4j distribué.
 */
@Component
public class RateLimiter {

    private static final Duration MAX_WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Enregistre une tentative.
     * @return 0 si autorisée, sinon le nombre de secondes avant la prochaine tentative possible
     */
    public long tryAcquire(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() <= windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                long retryMillis = deque.peekFirst() + window.toMillis() - now;
                return Math.max(1, (retryMillis + 999) / 1000);
            }
            deque.addLast(now);
            return 0;
        }
    }

    /** Purge des entrées inactives toutes les 5 minutes pour éviter une fuite mémoire. */
    @Scheduled(fixedDelay = 300_000)
    public void purge() {
        long threshold = System.currentTimeMillis() - MAX_WINDOW.toMillis();
        hits.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                return d.isEmpty() || d.peekLast() < threshold;
            }
        });
    }
}
