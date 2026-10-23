package dev.ikm.tinkar.service.auth;

import jakarta.servlet.DispatcherType;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

/**
 * Who may call the service: everyone, with authentication off; with it on, callers holding a
 * token from {@code POST /api/auth/login}, on REST and gRPC alike.
 *
 * <p>Open with authentication on: signing in and what the sign-in screen needs
 * ({@code /api/auth/**}), the API docs, and the health check. Stateless: no session and no
 * cookie, so no CSRF token either.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    TokenService tokenService(AuthProperties auth) {
        if (auth.enabled()) {
            LOG.info("Authentication is on: REST and gRPC calls need a token; credentials are read from {}",
                    auth.credentialsFile().toAbsolutePath());
            return new TokenService(auth.jwtSecret(), auth.tokenTtl(), Clock.systemUTC());
        }
        LOG.warn("Authentication is off (tinkar.auth.enabled=false): every endpoint is open,"
                + " and writes are stamped with the generic user");
        return TokenService.withRandomSecret(auth.tokenTtl());
    }

    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.decoder();
    }

    @Bean
    CredentialStore credentialStore(AuthProperties auth) {
        return new CredentialStore(auth.credentialsFile());
    }

    @Bean
    @GrpcGlobalServerInterceptor
    GrpcAuthInterceptor grpcAuthInterceptor(AuthProperties auth, TokenService tokens) {
        return new GrpcAuthInterceptor(auth.enabled(), tokens);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties auth) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        if (!auth.enabled()) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
            return http.build();
        }
        http.authorizeHttpRequests(requests -> requests
                        // A streamed response finishes on an async dispatch, after the request was
                        // let in; the token is not checked again there.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs", "/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Browsers on this machine, as before: the sample UI runs on a local Vite port. */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("http://localhost:*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of("Content-Disposition"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
