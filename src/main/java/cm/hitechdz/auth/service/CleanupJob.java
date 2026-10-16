package cm.hitechdz.auth.service;

import cm.hitechdz.auth.repository.PasswordResetCodeRepository;
import cm.hitechdz.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Purge quotidienne des refresh tokens et codes de réinitialisation expirés. */
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetCodeRepository resetCodeRepository;

    public CleanupJob(RefreshTokenRepository refreshTokenRepository, PasswordResetCodeRepository resetCodeRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.resetCodeRepository = resetCodeRepository;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Douala")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        int tokens = refreshTokenRepository.deleteExpired(now);
        int codes = resetCodeRepository.deleteExpired(now);
        log.info("Nettoyage : {} refresh token(s) et {} code(s) expirés supprimés", tokens, codes);
    }
}
