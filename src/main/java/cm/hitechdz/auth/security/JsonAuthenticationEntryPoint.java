package cm.hitechdz.auth.security;

import cm.hitechdz.auth.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 401 au format JSON de l'API. */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object tokenError = request.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_ERROR);
        String message;
        if (JwtAuthenticationFilter.TOKEN_EXPIRED.equals(tokenError)) {
            message = "Votre session a expiré. Veuillez vous reconnecter.";
        } else if (JwtAuthenticationFilter.ACCOUNT_DISABLED.equals(tokenError)) {
            message = "Votre compte a été désactivé. Contactez le support.";
        } else if (JwtAuthenticationFilter.TOKEN_INVALID.equals(tokenError)) {
            message = "Jeton d'authentification invalide.";
        } else {
            message = "Authentification requise.";
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("WWW-Authenticate", "Bearer");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(message));
    }
}
