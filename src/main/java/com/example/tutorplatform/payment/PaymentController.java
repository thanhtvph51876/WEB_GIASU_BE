package com.example.tutorplatform.payment;

import com.example.tutorplatform.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @GetMapping("/payments/settings")
  public ApiResponse<Map<String, Object>> paymentSettings() {
    return ApiResponse.ok(paymentService.settings());
  }

  @PostMapping("/payments/{paymentId}/create-checkout")
  public ApiResponse<Map<String, Object>> createCheckout(@PathVariable UUID paymentId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(paymentService.createCheckout(paymentId, body == null ? Map.of() : body), "Đã tạo phiên thanh toán.");
  }

  @GetMapping("/payments/{paymentId}/status")
  public ApiResponse<Map<String, Object>> paymentStatus(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.status(paymentId));
  }

  @GetMapping("/payments/{paymentId}/invoice")
  public ApiResponse<Map<String, Object>> invoice(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.invoice(paymentId));
  }

  @GetMapping("/payments/{paymentId}/receipt")
  public ApiResponse<Map<String, Object>> receipt(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.receipt(paymentId));
  }

  @PostMapping("/payments/webhooks/{gateway}")
  public ApiResponse<Map<String, Object>> webhook(
      @PathVariable String gateway,
      @RequestHeader Map<String, String> headers,
      @RequestBody String rawPayload
  ) {
    Map<String, String> normalizedHeaders = new LinkedHashMap<>();
    headers.forEach((key, value) -> normalizedHeaders.put(key.toLowerCase(), value));
    return ApiResponse.ok(paymentService.processWebhook(gateway, normalizedHeaders, rawPayload));
  }

  @GetMapping("/admin/payment-transactions")
  public ApiResponse<List<Map<String, Object>>> paymentTransactions() {
    return ApiResponse.ok(paymentService.transactions());
  }

  @GetMapping("/admin/payment-webhook-events")
  public ApiResponse<List<Map<String, Object>>> paymentWebhookEvents() {
    return ApiResponse.ok(paymentService.webhookEvents());
  }

  @GetMapping("/admin/refunds")
  public ApiResponse<List<Map<String, Object>>> refunds() {
    return ApiResponse.ok(paymentService.refunds());
  }
}
