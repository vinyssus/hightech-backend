package cm.hitechdz.auth.service.media;

import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/**
 * Stockage des images (implémentation Cloudinary). Interface séparée pour être mockée dans les tests.
 * Les fichiers reçus sont déjà validés (type, taille) par {@link ImageUploadService}.
 */
public interface ImageStorageService {

    String ROOT_FOLDER = "hitech-dz";
    String PRODUCTS_FOLDER = ROOT_FOLDER + "/products";
    String TMP_FOLDER = PRODUCTS_FOLDER + "/tmp";
    String BRANDS_FOLDER = ROOT_FOLDER + "/brands";

    StoredImage upload(MultipartFile file, String folder);

    void delete(String publicId);

    /** Déplace une image (ex. du dossier tmp vers le dossier du produit) et renvoie sa nouvelle URL. */
    StoredImage move(String publicId, String targetFolder);

    /** publicIds des images d'un dossier créées avant la date donnée. */
    List<String> listOlderThan(String folder, Instant before);

    static String productFolder(String slug) {
        return PRODUCTS_FOLDER + "/" + slug;
    }

    static boolean isTmp(String publicId) {
        return publicId != null && publicId.startsWith(TMP_FOLDER + "/");
    }

    /** Seules les images de notre dossier racine peuvent être déplacées ou supprimées. */
    static boolean isManaged(String publicId) {
        return publicId != null && publicId.startsWith(ROOT_FOLDER + "/");
    }
}
