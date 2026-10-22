package cm.hitechdz.auth.service.media;

import java.util.List;

/** Images Cloudinary à supprimer une fois la transaction validée. */
public record ImageDeletionRequested(List<String> publicIds) {}
