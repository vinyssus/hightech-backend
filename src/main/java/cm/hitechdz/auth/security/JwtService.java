package cm.hitechdz.auth.security;

import cm.hitechdz.auth.config.AppProperties;
import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Émission et validation des access tokens JWT (HS256).
 * Claims : sub = id utilisateur, email, role.
 */
@Service
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL = "email";

    private final SecretKey key;
    private final AppProperties.Jwt props;

    public JwtService(AppProperties appProperties) {
        this.props = appProperties.jwt();
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("La variable d'environnement JWT_SECRET est obligatoire.");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET doit être encodé en Base64 (openssl rand -base64 48).", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET doit faire au moins 32 octets (256 bits) une fois décodé.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /**
     * Valide la signature, l'émetteur et l'expiration.
     * @throws JwtException si le token est invalide ou expiré
     */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        try {
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
        } catch (RuntimeException e) {
            throw new io.jsonwebtoken.MalformedJwtException("Claims invalides", e);
        }
    }
}
