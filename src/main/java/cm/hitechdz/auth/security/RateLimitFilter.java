package cm.hitechdz.auth.security;

import cm.hitechdz.auth.config.AppProperties;
import cm.hitechdz.auth.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Limite le nombre de tentatives par adresse IP sur /login et /forgot-password.
 * Réponse 429 + header Retry-After en cas de dépassement.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN = "/api/auth/login";
    private static final String FORGOT = "/api/auth/forgot-password";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final AppProperties.RateLimit props;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper, AppProperties.RateLimit props) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = path(request);
        return !LOGIN.equals(path) && !FORGOT.equals(path);
    }

    /** Chemin sans le context-path ni slash final. */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        AppProperties.RateLimit.Rule rule = LOGIN.equals(path) ? props.login() : props.forgotPassword();
        // getRemoteAddr() renvoie la vraie IP client si server.forward-headers-strategy est configuré
        String key = path + "|" + request.getRemoteAddr();

        long retryAfter = rateLimiter.tryAcquire(key, rule.maxRequests(), rule.window());
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(),
                    ErrorResponse.of("Trop de tentatives. Veuillez réessayer dans quelques minutes."));
            return;
        }
        chain.doFilter(request, response);
    }
}
