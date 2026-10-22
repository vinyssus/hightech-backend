package cm.hitechdz.auth.controller;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.dto.AdminStatsDto;
import cm.hitechdz.auth.dto.AdminUserDto;
import cm.hitechdz.auth.dto.MessageResponse;
import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.UpdateRoleRequest;
import cm.hitechdz.auth.dto.UpdateStatusRequest;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Espace d'administration. Double protection :
 * règle d'URL /api/admin/** dans SecurityConfig + @PreAuthorize au niveau de la classe.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService adminUserService;

    public AdminController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/ping")
    public MessageResponse ping() {
        return new MessageResponse("Accès administrateur confirmé.");
    }

    @GetMapping("/stats")
    public AdminStatsDto stats() {
        return adminUserService.stats();
    }

    @GetMapping("/users")
    public PageResponse<AdminUserDto> users(@RequestParam(required = false) String search,
                                            @RequestParam(required = false) Role role,
                                            @RequestParam(required = false) Boolean enabled,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sort) {
        return adminUserService.list(search, role, enabled, page, size, sort);
    }

    @GetMapping("/users/{id}")
    public AdminUserDto user(@PathVariable UUID id) {
        return adminUserService.get(id);
    }

    @PutMapping("/users/{id}/role")
    public AdminUserDto changeRole(@AuthenticationPrincipal AuthenticatedUser admin,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody UpdateRoleRequest request) {
        return adminUserService.changeRole(admin.id(), id, request.role());
    }

    @PutMapping("/users/{id}/status")
    public AdminUserDto changeStatus(@AuthenticationPrincipal AuthenticatedUser admin,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody UpdateStatusRequest request) {
        return adminUserService.changeStatus(admin.id(), id, request.enabled());
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        adminUserService.delete(admin.id(), id);
    }
}
