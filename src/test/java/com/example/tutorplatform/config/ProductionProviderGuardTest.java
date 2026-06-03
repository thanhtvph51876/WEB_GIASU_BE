package com.example.tutorplatform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionProviderGuardTest {
  @Test
  void productionRejectsSandboxPaymentMode() {
    ProductionProviderGuard guard = new ProductionProviderGuard(
        properties("production", "sandbox", "sandbox", "bank_qr", "real"),
        new MockEnvironment()
    );

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("production payment mode");
  }

  @Test
  void productionRejectsSandboxPaymentProvider() {
    ProductionProviderGuard guard = new ProductionProviderGuard(
        properties("production", "sandbox", "production", "bank_qr", "real"),
        new MockEnvironment()
    );

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sandbox payment provider");
  }

  @Test
  void productionAllowsProductionModeWithRealProviders() {
    ProductionProviderGuard guard = new ProductionProviderGuard(
        properties("production", "payos", "production", "payos", "real"),
        new MockEnvironment()
    );

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  private AppProperties properties(String env, String paymentProvider, String paymentMode, String defaultGateway, String verificationProvider) {
    return new AppProperties(
        env,
        new AppProperties.Jwt("a-secure-jwt-secret-with-more-than-32-characters", 15, 7),
        new AppProperties.Cors(List.of("https://example.com")),
        new AppProperties.Upload("uploads", "", "local"),
        new AppProperties.Payment(paymentProvider, paymentMode, List.of(defaultGateway), defaultGateway, 15),
        new AppProperties.Verification(verificationProvider),
        new AppProperties.Auth("https://example.com", 30, 30, false),
        new AppProperties.Seed(false),
        0.15
    );
  }
}
