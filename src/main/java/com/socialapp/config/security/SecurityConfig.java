package com.socialapp.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security.
 * <ul>
 *   <li>Public: register, login, CORS preflight, uploaded images ({@code /uploads/**}).</li>
 *   <li>Everything else under {@code /api/**} requires a valid Bearer token
 *       (SSE stream additionally accepts {@code ?access_token=}).</li>
 *   <li>ASYNC/ERROR dispatches are permitted: the original REQUEST dispatch was already
 *       authorised, and the SecurityContext is not persisted between dispatches (stateless),
 *       so re-checking would break SSE completion and mask real errors as 401.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   ObjectMapper objectMapper) throws Exception {
        var jsonErrors = new JsonAuthenticationEntryPoint(objectMapper);

        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable) // no cookies/session: CSRF not applicable
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(jsonErrors)
                        .accessDeniedHandler(jsonErrors))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/users/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /** Same origins/methods as the former WebConfig#addCorsMappings, now applied by Spring Security. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:4200,http://localhost:4201}") List<String> allowedOrigins) {

        var api = new CorsConfiguration();
        api.setAllowedOrigins(allowedOrigins);
        api.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        api.setAllowedHeaders(List.of("*"));
        api.setExposedHeaders(List.of("Content-Type"));
        api.setAllowCredentials(true);

        var uploads = new CorsConfiguration();
        uploads.setAllowedOrigins(allowedOrigins);
        uploads.setAllowedMethods(List.of("GET"));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);
        source.registerCorsConfiguration("/uploads/**", uploads);
        return source;
    }
}
