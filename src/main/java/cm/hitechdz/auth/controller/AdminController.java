package cm.hitechdz.auth.controller;

import cm.hitechdz.auth.dto.MessageResponse;
import cm.hitechdz.auth.dto.UserDto;
import cm.hitechdz.auth.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exemple d'espace d'administration. Double protection :
 * règle d'URL /api/admin/** dans SecurityConfig + @PreAuthorize au niveau de la classe.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;

    public AdminController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/ping")
    public MessageResponse ping() {
        return new MessageResponse("Accès administrateur confirmé.");
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<UserDto> users() {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(UserDto::from).toList();
    }
}
