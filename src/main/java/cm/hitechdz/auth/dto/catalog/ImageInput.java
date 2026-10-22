package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Image envoyée avec un produit : { url, publicId } ou simplement une URL (String).
 * publicId null = URL externe.
 */
public record ImageInput(String url, String publicId) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ImageInput(@JsonProperty("url") String url, @JsonProperty("publicId") String publicId) {
        this.url = url;
        this.publicId = publicId;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ImageInput ofUrl(String url) {
        return new ImageInput(url, null);
    }
}
