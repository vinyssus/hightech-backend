package cm.hitechdz.auth.service.media;

import cm.hitechdz.auth.exception.ApiException;
import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CloudinaryImageStorageService implements ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorageService.class);
    static final String NOT_CONFIGURED = "Le stockage d'images n'est pas configuré sur le serveur.";
    static final String UPLOAD_FAILED = "L'envoi de l'image a échoué. Réessayez.";

    private final ObjectProvider<Cloudinary> cloudinaryProvider;

    public CloudinaryImageStorageService(ObjectProvider<Cloudinary> cloudinaryProvider) {
        this.cloudinaryProvider = cloudinaryProvider;
    }

    @Override
    public StoredImage upload(MultipartFile file, String folder) {
        Cloudinary cloudinary = client();
        Map<String, Object> options = new HashMap<>();
        options.put("folder", folder);
        options.put("resource_type", "image");
        options.put("unique_filename", true);
        options.put("overwrite", false);
        // Transformation à l'arrivée : 1200x1200 maximum, qualité automatique.
        // Le format (WebP/AVIF) est choisi à la diffusion via f_auto (voir CatalogMapper).
        options.put("transformation", new Transformation().width(1200).height(1200).crop("limit").quality("auto"));
        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), options);
            return toStoredImage(result);
        } catch (IOException | RuntimeException | LinkageError e) {
            // LinkageError : incompatibilité de bibliothèque, à journaliser plutôt que de renvoyer une 500 opaque
            log.error("Échec d'envoi Cloudinary dans {} : {}", folder, e.toString(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, UPLOAD_FAILED);
        }
    }

    @Override
    public void delete(String publicId) {
        if (!ImageStorageService.isManaged(publicId)) {
            return;
        }
        try {
            client().uploader().destroy(publicId, Map.of("invalidate", true, "resource_type", "image"));
        } catch (Exception | LinkageError e) {
            // Ne bloque jamais l'opération métier : on journalise seulement.
            log.error("Échec de suppression Cloudinary de {}", publicId, e);
        }
    }

    @Override
    public StoredImage move(String publicId, String targetFolder) {
        String baseName = publicId.substring(publicId.lastIndexOf('/') + 1);
        String target = targetFolder + "/" + baseName;
        try {
            Map<?, ?> result = client().uploader().rename(publicId, target,
                    Map.of("overwrite", false, "invalidate", true));
            return toStoredImage(result);
        } catch (ApiException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            log.error("Échec du déplacement Cloudinary de {} vers {}", publicId, target, e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, UPLOAD_FAILED);
        }
    }

    @Override
    public List<String> listOlderThan(String folder, Instant before) {
        Cloudinary cloudinary = cloudinaryProvider.getIfAvailable();
        if (cloudinary == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        String cursor = null;
        try {
            do {
                Map<String, Object> options = new HashMap<>();
                options.put("type", "upload");
                options.put("resource_type", "image");
                options.put("prefix", folder + "/");
                options.put("max_results", 500);
                if (cursor != null) {
                    options.put("next_cursor", cursor);
                }
                Map<?, ?> response = cloudinary.api().resources(options);
                Object resources = response.get("resources");
                if (resources instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> r && isOlder(r.get("created_at"), before)) {
                            result.add(String.valueOf(r.get("public_id")));
                        }
                    }
                }
                Object next = response.get("next_cursor");
                cursor = next == null ? null : next.toString();
            } while (cursor != null);
        } catch (Exception e) {
            log.error("Impossible de lister les images de {}", folder, e);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers
    private Cloudinary client() {
        Cloudinary cloudinary = cloudinaryProvider.getIfAvailable();
        if (cloudinary == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        return cloudinary;
    }

    private static boolean isOlder(Object createdAt, Instant before) {
        if (createdAt == null) {
            return false;
        }
        try {
            return Instant.parse(createdAt.toString()).isBefore(before);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static StoredImage toStoredImage(Map<?, ?> result) {
        return new StoredImage(
                String.valueOf(result.get("secure_url")),
                String.valueOf(result.get("public_id")),
                toInteger(result.get("width")),
                toInteger(result.get("height")));
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
