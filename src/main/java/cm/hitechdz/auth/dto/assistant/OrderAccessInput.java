package cm.hitechdz.auth.dto.assistant;

import jakarta.validation.constraints.Size;

/** Jeton invité d'une commande passée sur cet appareil (jamais transmis au modèle d'IA). */
public record OrderAccessInput(
        @Size(max = 24) String number,
        @Size(max = 200) String token
) {}
