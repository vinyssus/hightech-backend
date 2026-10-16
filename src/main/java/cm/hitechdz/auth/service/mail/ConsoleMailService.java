package cm.hitechdz.auth.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Implémentation de développement : affiche l'e-mail dans les logs. */
@Service
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMailService.class);

    @Override
    public void sendPasswordResetCode(String to, String firstName, String code, long validityMinutes) {
        log.info("""

                ==================== E-MAIL (console) ====================
                À       : {}
                Objet   : Votre code de réinitialisation HITECH DZ
                Bonjour {},
                Votre code de réinitialisation est : {}
                Il est valable {} minutes. Si vous n'êtes pas à l'origine
                de cette demande, ignorez simplement ce message.
                ==========================================================""",
                to, firstName, code, validityMinutes);
    }
}
