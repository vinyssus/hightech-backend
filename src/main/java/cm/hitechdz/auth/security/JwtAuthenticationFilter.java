package cm.hitechdz.auth.security;

import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.repository.UserRepository;
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
 *
 * Le compte est relu en base à chaque requête (recherche par clé primaire) : un blocage,
 * une suppression ou un changement de rôle par un administrateur s'applique immédiatement,
 * sans attendre l'expiration de l'access token.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Attribut de requête lu par l'entry point pour adapter le message 401. */
    public static final String ATTR_TOKEN_ERROR = "auth.tokenError";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_INVALID = "invalid";
    public static final String ACCOUNT_DISABLED = "disabled";

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                AuthenticatedUser claims = jwtService.parseAccessToken(token);
                User user = userRepository.findById(claims.id()).orElse(null);
                if (user == null) {
                    request.setAttribute(ATTR_TOKEN_ERROR, TOKEN_INVALID);
                } else if (!user.isEnabled()) {
                    request.setAttribute(ATTR_TOKEN_ERROR, ACCOUNT_DISABLED);
                } else {
                    // Rôle et e-mail lus en base : un changement de rôle prend effet immédiatement
                    AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);

                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(ATTR_TOKEN_ERROR, TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(ATTR_TOKEN_ERROR, TOKEN_INVALID);
            }
        }
        chain.doFilter(request, response);
    }
}
