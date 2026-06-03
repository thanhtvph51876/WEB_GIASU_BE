package com.example.tutorplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tutorplatform.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
  @Test
  void productionRejectsShortJwtSecret() {
    assertThatThrownBy(() -> new JwtService(properties("production", "short-secret")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void productionRejectsPlaceholderJwtSecret() {
    assertThatThrownBy(() -> new JwtService(properties("prod", "change-me-to-a-long-random-secret-at-least-32-bytes")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void localAllowsShortJwtSecretForDevOnly() {
    JwtService service = new JwtService(properties("local", "dev-secret"));
    String token = service.accessToken("user-1", "student");

    assertThat(service.parse(token).getSubject()).isEqualTo("user-1");
  }

  private AppProperties properties(String env, String secret) {
    return new AppProperties(
        env,
        new AppProperties.Jwt(secret, 15, 7),
        new AppProperties.Cors(List.of("http://localhost:3000")),
        new AppProperties.Upload("uploads", "", "local"),
        new AppProperties.Payment("mock", "sandbox", List.of("mock"), "mock", 15),
        new AppProperties.Verification("mock"),
        new AppProperties.Auth("http://localhost:3000", 30, 30, false),
        new AppProperties.Seed(false),
        0.15
    );
  }
}
