package cm.hitechdz.auth.dto.assistant;

import cm.hitechdz.auth.dto.catalog.ProductSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * reply : texte de l'assistant ; products : fiches citées (à afficher en cartes) ;
 * handoff : proposition de passer sur WhatsApp, avec un message pré-rempli.
 */
public record ChatResponse(String reply, List<ProductSummary> products,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Handoff handoff) {

    public record Handoff(String reason, String message) {}
}
