package cm.hitechdz.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// UserDetailsServiceAutoConfiguration est exclue : l'authentification est 100 % JWT,
// on évite ainsi la création d'un utilisateur en mémoire avec mot de passe généré.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class HitechDzAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(HitechDzAuthApplication.class, args);
    }
}
