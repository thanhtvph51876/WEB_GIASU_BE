package com.example.tutorplatform.config;

import com.example.tutorplatform.security.JwtAuthenticationFilter;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> {})
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers -> headers
            .contentTypeOptions(Customizer.withDefaults())
            .frameOptions(frame -> frame.sameOrigin())
            .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
        )
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint((request, response, authException) -> {
              response.setStatus(HttpStatus.UNAUTHORIZED.value());
              response.setContentType("application/json;charset=UTF-8");
              response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Bạn cần đăng nhập.\"}}");
            })
            .accessDeniedHandler((request, response, accessDeniedException) -> {
              response.setStatus(HttpStatus.FORBIDDEN.value());
              response.setContentType("application/json;charset=UTF-8");
              response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Bạn không có quyền thực hiện thao tác này.\"}}");
            })
        )
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/public/learning-requests").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/files/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/tutors/**", "/api/v1/catalog/**", "/api/v1/public/**", "/api/v1/master-data/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/contact-requests").permitAll()
            .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "FINANCE_ADMIN", "TUTOR_ADMIN", "SUPPORT_ADMIN", "VERIFICATION_ADMIN", "SYSTEM_ADMIN")
            .requestMatchers("/api/v1/tutor/**").hasRole("TUTOR")
            .anyRequest().authenticated()
        )
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.cors().allowedOrigins());
    config.setAllowedMethods(Arrays.asList("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
