package cm.hitechdz.auth.service.media;

import java.util.Optional;

/**
 * Lit le vrai format et les dimensions d'une image à partir de ses octets (en-têtes), sans la décoder :
 * pas de risque de « bombe de décompression ». Gère JPEG, PNG, GIF et WebP (VP8, VP8L, VP8X),
 * et reconnaît SVG, BMP, TIFF et HEIC/HEIF pour pouvoir les refuser explicitement.
 */
public final class ImageInspector {

    public enum Format {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), GIF("image/gif");

        private final String mime;

        Format(String mime) { this.mime = mime; }

        public String mime() { return mime; }

        public String key() { return name().toLowerCase(); }
    }

    /** Image reconnue et acceptée. */
    public record Info(Format format, int width, int height) {}

    /** Résultat : format accepté avec dimensions, ou nom d'un format refusé (SVG, BMP…), ou inconnu. */
    public record Result(Info info, String rejectedFormat) {
        public boolean accepted() { return info != null; }
    }

    private ImageInspector() {}

    public static Result inspect(byte[] b) {
        if (b == null || b.length < 12) {
            return new Result(null, null);
        }
        Optional<Info> info;
        if (u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) {
            info = jpeg(b);
        } else if (u8(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            info = png(b);
        } else if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            info = gif(b);
        } else if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            info = webp(b);
        } else {
            return new Result(null, rejectedName(b));
        }
        return info.filter(i -> i.width() > 0 && i.height() > 0)
                .map(i -> new Result(i, null))
                .orElseGet(() -> new Result(null, null));
    }

    // ------------------------------------------------------------------ formats acceptés
    private static Optional<Info> png(byte[] b) {
        // Signature (8) + longueur (4) + « IHDR » (4) + largeur (4) + hauteur (4), en gros-boutiste
        if (b.length < 24 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return Optional.empty();
        }
        return Optional.of(new Info(Format.PNG, be32(b, 16), be32(b, 20)));
    }

    private static Optional<Info> gif(byte[] b) {
        return Optional.of(new Info(Format.GIF, le16(b, 6), le16(b, 8)));
    }

    private static Optional<Info> jpeg(byte[] b) {
        int i = 2;
        while (i + 3 < b.length) {
            if (u8(b, i) != 0xFF) {
                return Optional.empty();
            }
            int marker = u8(b, i + 1);
            if (marker == 0xFF) {          // octets de remplissage
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;                    // marqueurs sans longueur
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty();   // fin d'image ou début des données sans SOF
            }
            int length = be16(b, i + 2);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) {
                    return Optional.empty();
                }
                return Optional.of(new Info(Format.JPEG, be16(b, i + 7), be16(b, i + 5)));
            }
            if (length < 2) {
                return Optional.empty();
            }
            i += 2 + length;
        }
        return Optional.empty();
    }

    private static Optional<Info> webp(byte[] b) {
        if (b.length < 30) {
            return Optional.empty();
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X" -> {
                // Largeur et hauteur du canevas moins 1, sur 24 bits petit-boutiste
                return Optional.of(new Info(Format.WEBP, le24(b, 24) + 1, le24(b, 27) + 1));
            }
            case "VP8 " -> {
                // Code de début 9D 01 2A, puis largeur et hauteur sur 14 bits
                if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) {
                    return Optional.empty();
                }
                return Optional.of(new Info(Format.WEBP, le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF));
            }
            case "VP8L" -> {
                if (u8(b, 20) != 0x2F) {
                    return Optional.empty();
                }
                long bits = u8(b, 21) | ((long) u8(b, 22) << 8) | ((long) u8(b, 23) << 16) | ((long) u8(b, 24) << 24);
                return Optional.of(new Info(Format.WEBP, (int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Animation d'un GIF : nombre d'images et durée totale d'une boucle. */
    public record GifAnimation(int frames, int durationMs) {}

    /**
     * Parcourt les blocs d'un GIF (sans décoder les pixels) pour compter les images et additionner leurs délais.
     * Un délai de 0 ou 1 centième est compté 100 ms, comme le font les navigateurs. Vide si le GIF est illisible.
     */
    public static Optional<GifAnimation> gifAnimation(byte[] b) {
        if (b == null || b.length < 13 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') {
            return Optional.empty();
        }
        int i = 13;
        int packed = u8(b, 10);
        if ((packed & 0x80) != 0) {
            i += 3 * (1 << ((packed & 0x07) + 1));
        }
        int frames = 0;
        long durationCs = 0;
        int pendingDelay = 0;
        while (i < b.length) {
            int block = u8(b, i);
            if (block == 0x3B) {                  // fin du fichier
                break;
            }
            if (block == 0x21) {                  // extension
                if (i + 2 >= b.length) return Optional.empty();
                int label = u8(b, i + 1);
                i += 2;
                if (label == 0xF9 && i + 4 < b.length && u8(b, i) == 4) {
                    pendingDelay = le16(b, i + 2);
                }
                i = skipSubBlocks(b, i);
            } else if (block == 0x2C) {           // image
                if (i + 10 >= b.length) return Optional.empty();
                frames++;
                durationCs += pendingDelay <= 1 ? 10 : pendingDelay;
                pendingDelay = 0;
                int localPacked = u8(b, i + 9);
                i += 10;
                if ((localPacked & 0x80) != 0) {
                    i += 3 * (1 << ((localPacked & 0x07) + 1));
                }
                i += 1;                           // taille minimale du code LZW
                i = skipSubBlocks(b, i);
            } else {
                return Optional.empty();
            }
            if (i < 0) {
                return Optional.empty();
            }
        }
        return frames == 0 ? Optional.empty() : Optional.of(new GifAnimation(frames, (int) Math.min(Integer.MAX_VALUE, durationCs * 10)));
    }

    /** Saute une suite de sous-blocs (longueur + données) terminée par 0 ; -1 si tronquée. */
    private static int skipSubBlocks(byte[] b, int i) {
        while (i < b.length) {
            int size = u8(b, i);
            if (size == 0) {
                return i + 1;
            }
            i += size + 1;
        }
        return -1;
    }

    // ------------------------------------------------------------------ formats refusés (message clair)
    private static String rejectedName(byte[] b) {
        if (b[0] == 'B' && b[1] == 'M') {
            return "BMP";
        }
        if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42)) {
            return "TIFF";
        }
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1")) {
                return "HEIC";
            }
            if (brand.startsWith("avi")) {
                return "AVIF";
            }
        }
        String head = new String(b, 0, Math.min(b.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .trim().toLowerCase();
        if (head.startsWith("<?xml") || head.startsWith("<svg") || head.contains("<svg")) {
            return "SVG";
        }
        return null;
    }

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }
    private static int be16(byte[] b, int i) { return (u8(b, i) << 8) | u8(b, i + 1); }
    private static int be32(byte[] b, int i) { return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3); }
    private static int le16(byte[] b, int i) { return u8(b, i) | (u8(b, i + 1) << 8); }
    private static int le24(byte[] b, int i) { return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16); }
}
