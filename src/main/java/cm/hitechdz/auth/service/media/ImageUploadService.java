package cm.hitechdz.auth.service.media;

import cm.hitechdz.auth.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validation des fichiers image avant envoi : JPEG, PNG ou WebP (vérifiés par leur signature binaire,
 * pas seulement par leur extension), 5 Mo maximum par fichier.
 */
@Service
public class ImageUploadService {

    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    public static final int MAX_IMAGES_PER_PRODUCT = 8;

    static final String NO_FILE = "Aucun fichier reçu.";
    static final String BAD_TYPE = "Format non accepté : seules les images JPEG, PNG ou WebP sont autorisées.";
    static final String TOO_BIG = "Fichier trop volumineux : 5 Mo maximum par image.";
    static final String TOO_MANY = "8 images maximum par produit.";

    private final ImageStorageService storage;

    public ImageUploadService(ImageStorageService storage) {
        this.storage = storage;
    }

    /** Valide puis envoie les fichiers. Tout est validé avant le premier envoi. */
    public List<StoredImage> uploadAll(List<MultipartFile> files, String folder, int alreadyAttached) {
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NO_FILE, Map.of("files", NO_FILE));
        }
        if (alreadyAttached + files.size() > MAX_IMAGES_PER_PRODUCT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TOO_MANY, Map.of("files", TOO_MANY));
        }
        files.forEach(ImageUploadService::validate);

        List<StoredImage> stored = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            stored.add(storage.upload(file, folder));
        }
        return stored;
    }

    public StoredImage uploadOne(MultipartFile file, String folder) {
        validate(file);
        return storage.upload(file, folder);
    }

    static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NO_FILE, Map.of("files", NO_FILE));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TOO_BIG, Map.of("files", TOO_BIG));
        }
        if (!hasImageSignature(file)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, BAD_TYPE, Map.of("files", BAD_TYPE));
        }
    }

    private static boolean hasImageSignature(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            return false;
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return true;    // JPEG
        }
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return true;    // PNG
        }
        return read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';   // WebP
    }
}
