package com.example.tutorplatform.payment.gateway;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.config.AppProperties;
import com.example.tutorplatform.db.DbService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentGatewayFactory {
  private final DbService db;
  private final AppProperties properties;
  private final Map<PaymentGatewayType, PaymentGateway> gateways = new EnumMap<>(PaymentGatewayType.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PaymentGatewayFactory(DbService db, AppProperties properties, List<PaymentGateway> gatewayList) {
    this.db = db;
    this.properties = properties;
    for (PaymentGateway gateway : gatewayList) {
      gateways.put(gateway.type(), gateway);
    }
  }

  public PaymentGateway resolve(String requestedGateway) {
    String gatewayCode = requestedGateway == null || requestedGateway.isBlank() ? setting("defaultGateway", properties.payment().defaultGateway()) : requestedGateway;
    PaymentGatewayType type;
    try {
      type = PaymentGatewayType.from(gatewayCode);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("UNKNOWN_PAYMENT_GATEWAY", "Cổng thanh toán không hợp lệ.", HttpStatus.BAD_REQUEST);
    }
    if (!enabledGateways().contains(type.code())) {
      throw new BusinessException("PAYMENT_GATEWAY_DISABLED", "Cổng thanh toán đang bị tắt.", HttpStatus.BAD_REQUEST);
    }
    String mode = paymentMode().toLowerCase();
    if ("production".equals(mode)) {
      throw new BusinessException("REAL_GATEWAY_REQUIRED", "Production cần adapter gateway thật trước khi nhận thanh toán.", HttpStatus.FORBIDDEN);
    }
    PaymentGateway gateway = gateways.get(type);
    if (gateway == null) {
      throw new BusinessException("PAYMENT_GATEWAY_NOT_CONFIGURED", "Cổng thanh toán chưa được cấu hình.", HttpStatus.BAD_REQUEST);
    }
    return gateway;
  }

  public String paymentMode() {
    return setting("paymentMode", properties.payment().mode());
  }

  public int paymentTimeoutMinutes() {
    String value = setting("paymentTimeoutMinutes", String.valueOf(properties.payment().timeoutMinutes()));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return 30;
    }
  }

  public String setting(String key, String fallback) {
    return db.optional("select value::text from system_settings where key = ?", (rs, row) -> rs.getString(1), key)
        .map(value -> value.replace("\"", ""))
        .orElse(fallback);
  }

  public List<String> enabledGateways() {
    String raw = db.optional("select value::text from system_settings where key = ?", (rs, row) -> rs.getString(1), "enabledGateways")
        .orElse(null);
    if (raw == null) {
      return properties.payment().enabledGateways().stream()
          .map(value -> value.toLowerCase().replace("-", "_"))
          .toList();
    }
    try {
      return objectMapper.readValue(raw, new TypeReference<List<String>>() {}).stream()
          .map(value -> value.toLowerCase().replace("-", "_"))
          .toList();
    } catch (Exception ex) {
      return List.of("bank_qr");
    }
  }
}
