package cm.hitechdz.auth.controller.catalog;

import cm.hitechdz.auth.dto.catalog.FavoritesMergeRequest;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.service.catalog.FavoriteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Favoris du client connecté. */
@RestController
@RequestMapping("/api/me/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping
    public List<ProductSummary> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return favoriteService.list(user.id());
    }

    @PutMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String productId) {
        favoriteService.add(user.id(), productId);
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String productId) {
        favoriteService.remove(user.id(), productId);
    }

    /** Fusion à la connexion des favoris enregistrés dans le navigateur sans compte. */
    @PostMapping("/merge")
    public List<ProductSummary> merge(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody FavoritesMergeRequest request) {
        return favoriteService.merge(user.id(), request.productIds());
    }
}
