package cm.hitechdz.auth.service.media;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Supprime les images sur Cloudinary APRÈS le commit : si la transaction échoue,
 * les images restent en place. Une erreur Cloudinary est journalisée sans bloquer.
 */
@Component
public class ImageDeletionListener {

    private final ImageStorageService storage;

    public ImageDeletionListener(ImageStorageService storage) {
        this.storage = storage;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeletionRequested(ImageDeletionRequested event) {
        for (String publicId : event.publicIds()) {
            if (publicId != null) {
                storage.delete(publicId);
            }
        }
    }
}
