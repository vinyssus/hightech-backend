package cm.hitechdz.auth.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lit le header "Authorization: Bearer <token>", valide le JWT et peuple le SecurityContext.
 * Un token absent ou invalide ne bloque pas ici : c'est la règle d'autorisation de la route
 * qui déclenchera un 401 (via JsonAuthenticationEntryPoint).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Attribut de requête lu par l'entry point pour adapter le message 401. */
    public static final String ATTR_TOKEN_ERROR = "auth.tokenError";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_INVALID = "invalid";

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                AuthenticatedUser principal = jwtService.parseAccessToken(token);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (ExpiredJwtException e) {
                request.setAttribute(ATTR_TOKEN_ERROR, TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(ATTR_TOKEN_ERROR, TOKEN_INVALID);
            }
        }
        chain.doFilter(request, response);
    }
}
