package com.example.tutorplatform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Jwt jwt,
    Cors cors,
    Upload upload,
    Payment payment,
    Auth auth,
    double commissionRate
) {
  public record Jwt(String secret, long accessTokenExpireMinutes, long refreshTokenExpireDays) {
    public Duration accessTtl() {
      return Duration.ofMinutes(accessTokenExpireMinutes);
    }

    public Duration refreshTtl() {
      return Duration.ofDays(refreshTokenExpireDays);
    }
  }

  public record Cors(List<String> allowedOrigins) {}

  public record Upload(String dir, String publicBaseUrl) {}

  public record Payment(String mode, List<String> enabledGateways, String defaultGateway, int timeoutMinutes) {}

  public record Auth(
      String frontendBaseUrl,
      long passwordResetTokenExpireMinutes,
      long emailVerificationTokenExpireMinutes,
      boolean exposeDevTokens
  ) {
    public Duration passwordResetTtl() {
      return Duration.ofMinutes(passwordResetTokenExpireMinutes);
    }

    public Duration emailVerificationTtl() {
      return Duration.ofMinutes(emailVerificationTokenExpireMinutes);
    }
  }
}
