package cm.hitechdz.auth.dto.order;

/** Jeton à envoyer dans l'en-tête X-Guest-Order-Token (jamais dans une URL). */
public record OrderLookupResponse(String guestAccessToken) {}
