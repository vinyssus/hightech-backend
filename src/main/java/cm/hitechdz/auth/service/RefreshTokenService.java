package cm.hitechdz.auth.service;

import cm.hitechdz.auth.config.AppProperties;
import cm.hitechdz.auth.domain.RefreshToken;
import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Gestion des refresh tokens opaques : émission, rotation, révocation.
 * Détection de réutilisation : présenter un token déjà révoqué révoque toute la famille
 * de sessions de l'utilisateur (signe probable de vol de token).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    static final String INVALID_SESSION = "Session invalide ou expirée. Veuillez vous reconnecter.";

    private final RefreshTokenRepository repository;
    private final AppProperties.Jwt props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AppProperties appProperties) {
        this.repository = repository;
        this.props = appProperties.jwt();
    }

    /** Crée un refresh token et renvoie sa valeur brute (seul le hash est stocké). */
    @Transactional
    public String issue(User user, boolean remember) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(raw));
        token.setRememberMe(remember);
        token.setExpiresAt(Instant.now().plus(remember ? props.refreshTokenTtlRemember() : props.refreshTokenTtlSession()));
        repository.save(token);
        return raw;
    }

    /** Résultat d'une rotation. */
    public record Rotation(User user, String refreshToken) {}

    /**
     * Valide le refresh token, le révoque et en émet un nouveau (même durée de vie "remember").
     * noRollbackFor : la révocation de masse en cas de réutilisation doit être conservée.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String raw) {
        RefreshToken current = repository.findByTokenHashWithUser(hash(raw))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION));

        if (current.isRevoked()) {
            UUID userId = current.getUser().getId();
            int n = repository.revokeAllForUser(userId, Instant.now());
            log.warn("Réutilisation d'un refresh token révoqué pour l'utilisateur {} : {} session(s) révoquée(s)", userId, n);
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION);
        }
        if (current.isExpired(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION);
        }

        current.revoke();
        User user = current.getUser();
        String next = issue(user, current.isRememberMe());
        return new Rotation(user, next);
    }

    /** Révoque un refresh token (déconnexion). Idempotent, silencieux si le token est inconnu. */
    @Transactional
    public void revoke(String raw) {
        repository.findByTokenHash(hash(raw)).ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public void revokeAll(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
