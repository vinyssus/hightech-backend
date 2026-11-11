package cm.hitechdz.auth.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client de l'API Messages d'Anthropic. Clé lue uniquement côté serveur (ANTHROPIC_API_KEY sur Render) :
 * sans clé, l'assistant est désactivé (aucune réponse simulée).
 */
@Component
public class AnthropicAssistantModel implements AssistantModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantModel.class);
    static final String UNAVAILABLE = "L'assistant est momentanément indisponible. Écrivez-nous sur WhatsApp.";

    private final RestClient client;
    private final boolean configured;
    private final String model;
    private final int maxTokens;
    private final ObjectMapper json;

    public AnthropicAssistantModel(@Value("${app.assistant.api-key:}") String apiKey,
                                   @Value("${app.assistant.model:claude-haiku-5-5}") String model,
                                   @Value("${app.assistant.max-tokens:1024}") int maxTokens,
                                   @Value("${app.assistant.base-url:https://api.anthropic.com}") String baseUrl,
                                   ObjectMapper json) {
        this.configured = apiKey != null && !apiKey.isBlank();
        this.model = model;
        this.maxTokens = maxTokens;
        this.json = json;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(45_000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts)
                .defaultHeader("x-api-key", configured ? apiKey.trim() : "")
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        if (configured) {
            log.info("Assistant IA activé (modèle {}).", model);
        } else {
            log.info("Assistant IA désactivé : ANTHROPIC_API_KEY absente.");
        }
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public JsonNode send(ObjectNode request) {
        if (!configured) {
            throw new AssistantUnavailableException(UNAVAILABLE);
        }
        ObjectNode body = request.deepCopy();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        try {
            String raw = client.post().uri("/v1/messages").body(json.writeValueAsString(body))
                    .retrieve().body(String.class);
            return json.readTree(raw);
        } catch (RestClientResponseException e) {
            // Pas le contenu de la conversation dans les journaux : seulement le statut et le type d'erreur
            String type = "";
            try {
                type = json.readTree(e.getResponseBodyAsString()).path("error").path("type").asText("");
            } catch (Exception ignored) {
                // corps illisible
            }
            log.warn("API Anthropic : HTTP {} {}", e.getStatusCode().value(), type);
            throw new AssistantUnavailableException(UNAVAILABLE);
        } catch (Exception e) {
            log.warn("API Anthropic injoignable : {}", e.getClass().getSimpleName());
            throw new AssistantUnavailableException(UNAVAILABLE);
        }
    }
}
