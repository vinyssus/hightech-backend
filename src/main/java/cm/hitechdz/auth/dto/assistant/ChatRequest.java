package cm.hitechdz.auth.dto.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @NotEmpty(message = "La conversation est vide.")
        @Size(max = 30, message = "Conversation trop longue : recommencez une nouvelle discussion.")
        List<@Valid ChatMessage> messages,
        @Size(max = 10)
        List<@Valid OrderAccessInput> guestOrders
) {}
