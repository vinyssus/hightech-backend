package cm.hitechdz.auth.config;

import cm.hitechdz.auth.repository.UserRepository;
import cm.hitechdz.auth.security.JsonAccessDeniedHandler;
import cm.hitechdz.auth.security.JsonAuthenticationEntryPoint;
import cm.hitechdz.auth.security.JwtAuthenticationFilter;
import cm.hitechdz.auth.security.JwtService;
import cm.hitechdz.auth.security.RateLimitFilter;
import cm.hitechdz.auth.security.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/cart/validate"
    };

    /** Catalogue public en lecture (les avis se publient connecté : POST non concerné). */
    private static final String[] PUBLIC_GET_ENDPOINTS = {
            "/api/products",
            "/api/products/**",
            "/api/categories",
            "/api/brands"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   RateLimiter rateLimiter,
                                                   ObjectMapper objectMapper,
                                                   AppProperties props,
                                                   UserRepository userRepository) throws Exception {
        // Les filtres sont instanciés ici (et non en @Component) pour éviter
        // qu'ils soient enregistrés une seconde fois dans la chaîne servlet.
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepository);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimiter, objectMapper, props.rateLimit());

        http
                .csrf(AbstractHttpConfigurer::disable)           // API stateless, pas de cookie de session
                .cors(cors -> {})                                // utilise le bean corsConfigurationSource
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Coût BCrypt configurable (APP_SECURITY_BCRYPT_STRENGTH). 10 par défaut : sûr et adapté
     * aux petites instances. Les hash existants restent valides quel que soit leur coût.
     */
    /**
     * Autorise les « / » encodés (%2F) dans l'URL : nécessaire pour
     * DELETE /api/admin/products/{id}/images/{publicId} (publicId = hitech-dz/products/slug/xxx).
     */
    @Bean
    public WebSecurityCustomizer allowEncodedSlash() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return web -> web.httpFirewall(firewall);
    }

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        // Motifs acceptés : une origine exacte ou un joker (ex. https://*.lovable.app pour les prévisualisations)
        config.setAllowedOriginPatterns(props.cors().allowedOrigins().stream().map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
