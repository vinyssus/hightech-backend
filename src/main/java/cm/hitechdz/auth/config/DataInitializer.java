package cm.hitechdz.auth.config;

import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crée le compte administrateur de démonstration au démarrage s'il n'existe pas.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties props) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Admin admin = props.admin();
        if (admin == null || !admin.seedEnabled()) {
            return;
        }
        String email = admin.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User user = new User();
        user.setFirstName("Admin");
        user.setLastName("HITECH DZ");
        user.setEmail(email);
        user.setPhone("6 00 00 00 00");
        user.setPasswordHash(passwordEncoder.encode(admin.password()));
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        log.warn("Compte administrateur de démonstration créé : {} (désactivez ADMIN_SEED_ENABLED en production)", email);
    }
}
