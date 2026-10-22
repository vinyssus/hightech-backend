package cm.hitechdz.auth.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * Envoi via l'API HTTP de Brevo (port 443).
 * Fonctionne là où les ports SMTP sont bloqués (offre gratuite de Render).
 * Activer avec MAIL_PROVIDER=brevo + BREVO_API_KEY + MAIL_FROM (expéditeur vérifié dans Brevo).
 */
@Service
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(BrevoMailService.class);

    private final RestClient client;
    private final String fromEmail;
    private final String fromName;

    public BrevoMailService(@Value("${app.mail.brevo-api-key:}") String apiKey,
                            @Value("${app.mail.from}") String fromEmail,
                            @Value("${app.mail.from-name:HITECH DZ}") String fromName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("La variable BREVO_API_KEY est obligatoire quand MAIL_PROVIDER=brevo.");
        }
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(10_000);

        this.client = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .requestFactory(timeouts)
                .defaultHeader("api-key", apiKey.trim())
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void sendPasswordResetCode(String to, String firstName, String code, long validityMinutes) {
        String safeName = HtmlUtils.htmlEscape(firstName);

        String html = """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:auto;color:#1f2937">
                  <h2 style="color:#111827">HITECH DZ</h2>
                  <p>Bonjour %s,</p>
                  <p>Voici votre code de réinitialisation du mot de passe :</p>
                  <p style="font-size:32px;font-weight:bold;letter-spacing:8px;text-align:center;
                            background:#f3f4f6;padding:16px;border-radius:8px">%s</p>
                  <p>Ce code est valable <strong>%d minutes</strong>.</p>
                  <p style="color:#6b7280;font-size:13px">Si vous n'êtes pas à l'origine de cette demande,
                     ignorez simplement ce message : votre mot de passe reste inchangé.</p>
                  <p>L'équipe HITECH DZ</p>
                </div>
                """.formatted(safeName, code, validityMinutes);

        String text = """
                Bonjour %s,

                Votre code de réinitialisation HITECH DZ est : %s
                Il est valable %d minutes.

                Si vous n'êtes pas à l'origine de cette demande, ignorez ce message.
                """.formatted(firstName, code, validityMinutes);

        Map<String, Object> body = Map.of(
                "sender", Map.of("name", fromName, "email", fromEmail),
                "to", List.of(Map.of("email", to, "name", firstName)),
                "subject", "Votre code de réinitialisation HITECH DZ",
                "htmlContent", html,
                "textContent", text);

        try {
            client.post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("E-mail de réinitialisation envoyé via Brevo à {}", to);
        } catch (RestClientResponseException e) {
            // On ne fait pas échouer la requête : la réponse API doit rester identique (anti-énumération).
            log.error("Échec d'envoi de l'e-mail de réinitialisation à {} : HTTP {} - {}",
                    to, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            log.error("Échec d'envoi de l'e-mail de réinitialisation à {}", to, e);
        }
    }
}
