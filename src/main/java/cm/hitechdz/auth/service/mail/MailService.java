package cm.hitechdz.auth.service.mail;

/**
 * Envoi des e-mails transactionnels.
 * Implémentations : ConsoleMailService (dev, par défaut) et SmtpMailService (MAIL_PROVIDER=smtp).
 */
public interface MailService {

    void sendPasswordResetCode(String to, String firstName, String code, long validityMinutes);
}
