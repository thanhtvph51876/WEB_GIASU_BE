package com.example.tutorplatform.config;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionProviderGuard implements ApplicationRunner {
  private final AppProperties properties;
  private final Environment environment;

  public ProductionProviderGuard(AppProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!strictProfile()) return;
    String paymentProvider = normalized(firstNonBlank(properties.payment().provider(), properties.payment().mode(), properties.payment().defaultGateway()));
    String verificationProvider = normalized(properties.verification().provider());
    if (isMock(paymentProvider) || normalized(properties.payment().defaultGateway()).equals("mock")) {
      throw new IllegalStateException("Mock payment provider is not allowed in staging/prod.");
    }
    if (isMock(verificationProvider)) {
      throw new IllegalStateException("Mock verification provider is not allowed in staging/prod.");
    }
  }

  private boolean strictProfile() {
    String appEnv = normalized(properties.env());
    if (appEnv.equals("prod") || appEnv.equals("production") || appEnv.equals("staging")) return true;
    return Arrays.stream(environment.getActiveProfiles())
        .map(this::normalized)
        .anyMatch(profile -> profile.equals("prod") || profile.equals("production") || profile.equals("staging"));
  }

  private boolean isMock(String value) {
    return value.equals("mock") || value.equals("simulated") || value.equals("sandbox_mock");
  }

  private String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "";
  }
}
