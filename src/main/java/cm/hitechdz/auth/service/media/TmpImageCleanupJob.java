package cm.hitechdz.auth.service.media;

import cm.hitechdz.auth.repository.catalog.ProductImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Purge quotidienne des images envoyées (dossier tmp ou racine des produits) jamais rattachées à un produit. */
@Component
public class TmpImageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TmpImageCleanupJob.class);

    private final ImageStorageService storage;
    private final ProductImageRepository imageRepository;

    public TmpImageCleanupJob(ImageStorageService storage, ProductImageRepository imageRepository) {
        this.storage = storage;
        this.imageRepository = imageRepository;
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "Africa/Douala")
    public void purge() {
        Instant before = Instant.now().minus(Duration.ofHours(24));
        int deleted = 0;
        for (String publicId : storage.listOlderThan(ImageStorageService.PRODUCTS_FOLDER, before)) {
            if (ImageStorageService.isPending(publicId) && !imageRepository.existsByPublicId(publicId)) {
                storage.delete(publicId);
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("Nettoyage : {} image(s) temporaire(s) non rattachée(s) supprimée(s)", deleted);
        }
    }
}
