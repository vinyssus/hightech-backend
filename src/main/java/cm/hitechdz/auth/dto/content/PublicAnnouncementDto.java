package cm.hitechdz.auth.dto.content;

/** Annonce publique : link vaut null si l'annonce n'est pas cliquable. */
public record PublicAnnouncementDto(Long id, String message, String link) {}
