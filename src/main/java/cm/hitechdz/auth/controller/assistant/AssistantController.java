package cm.hitechdz.auth.controller.assistant;

import cm.hitechdz.auth.dto.assistant.AssistantStatus;
import cm.hitechdz.auth.dto.assistant.ChatRequest;
import cm.hitechdz.auth.dto.assistant.ChatResponse;
import cm.hitechdz.auth.exception.RateLimitedException;
import cm.hitechdz.auth.security.RateLimiter;
import cm.hitechdz.auth.service.assistant.AssistantService;
import cm.hitechdz.auth.service.order.OrderAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Assistant IA de la boutique (public ; connecté = accès à ses commandes). */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistant;
    private final RateLimiter rateLimiter;
    private final int perIp;

    public AssistantController(AssistantService assistant, RateLimiter rateLimiter,
                               @Value("${app.assistant.max-messages-per-ip:20}") int perIp) {
        this.assistant = assistant;
        this.rateLimiter = rateLimiter;
        this.perIp = perIp;
    }

    @GetMapping
    public AssistantStatus status() {
        return assistant.status();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        long retryAfter = rateLimiter.tryAcquire("assistant|" + http.getRemoteAddr(), perIp, Duration.ofMinutes(10));
        if (retryAfter > 0) {
            throw new RateLimitedException("Vous envoyez beaucoup de messages. Réessayez dans quelques minutes.", retryAfter);
        }
        OrderAccess caller = OrderAccess.current(null);
        return assistant.chat(request, caller.userId(), caller.admin());
    }
}
