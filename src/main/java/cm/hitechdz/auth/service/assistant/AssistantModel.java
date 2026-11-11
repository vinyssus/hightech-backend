package cm.hitechdz.auth.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fournisseur du modèle d'IA (API Messages d'Anthropic). Remplaçable dans les tests. */
public interface AssistantModel {

    boolean configured();

    /** Envoie une requête Messages (sans model ni max_tokens, ajoutés ici) et renvoie la réponse JSON. */
    JsonNode send(ObjectNode request);
}
