package cm.hitechdz.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Format unique des erreurs : { "message": "...", "fieldErrors": { "champ": "..." } } */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String message, Map<String, String> fieldErrors) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(message, null);
    }
}
