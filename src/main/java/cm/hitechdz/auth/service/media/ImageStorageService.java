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

    /**
     * Image envoyée mais pas encore rattachée à un produit : dossier tmp (/api/admin/uploads/images)
     * ou racine de hitech-dz/products (/api/admin/uploads). Elle est déplacée à l'enregistrement du produit.
     */
    static boolean isPending(String publicId) {
        if (publicId == null) {
            return false;
        }
        if (isTmp(publicId)) {
            return true;
        }
        String prefix = PRODUCTS_FOLDER + "/";
        return publicId.startsWith(prefix) && publicId.indexOf('/', prefix.length()) < 0;
    }

    /**
     * Retrouve le publicId d'une URL Cloudinary de nos dossiers
     * (…/image/upload/[transformations/][v123/]hitech-dz/…/nom.ext), sinon null.
     */
    static String publicIdFromUrl(String url) {
        if (url == null || !url.contains("res.cloudinary.com") || !url.contains("/upload/")) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int start = path.indexOf("/" + ROOT_FOLDER + "/", path.indexOf("/upload/"));
        if (start < 0) {
            return null;
        }
        String id = path.substring(start + 1);
        int dot = id.lastIndexOf('.');
        if (dot > id.lastIndexOf('/')) {
            id = id.substring(0, dot);
        }
        return id;
    }

    /** Seules les images de notre dossier racine peuvent être déplacées ou supprimées. */
    static boolean isManaged(String publicId) {
        return publicId != null && publicId.startsWith(ROOT_FOLDER + "/");
    }
}
