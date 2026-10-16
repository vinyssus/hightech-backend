package cm.hitechdz.auth.service.mail;

import cm.hitechdz.auth.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Implémentation SMTP (MAIL_PROVIDER=smtp + SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD). */
@Service
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties props;

    public SmtpMailService(JavaMailSender mailSender, AppProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public void sendPasswordResetCode(String to, String firstName, String code, long validityMinutes) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.mail().from());
        msg.setTo(to);
        msg.setSubject("Votre code de réinitialisation HITECH DZ");
        msg.setText("""
                Bonjour %s,

                Votre code de réinitialisation est : %s
                Il est valable %d minutes.

                Si vous n'êtes pas à l'origine de cette demande, ignorez simplement ce message.

                L'équipe HITECH DZ
                """.formatted(firstName, code, validityMinutes));
        try {
            mailSender.send(msg);
        } catch (MailException e) {
            // On ne fait pas échouer la requête : la réponse doit rester identique (anti-énumération).
            log.error("Échec d'envoi de l'e-mail de réinitialisation à {}", to, e);
        }
    }
}
