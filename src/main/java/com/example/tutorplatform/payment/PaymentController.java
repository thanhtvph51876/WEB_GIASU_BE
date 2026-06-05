package com.example.tutorplatform.payment;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.PageMetadata;
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
import org.springframework.web.bind.annotation.RequestParam;
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
  public ApiResponse<List<Map<String, Object>>> paymentTransactions(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String gateway,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        paymentService.transactions(status, gateway, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, paymentService.transactionsCount(status, gateway, search))
    );
  }

  @GetMapping("/admin/payment-webhook-events")
  public ApiResponse<List<Map<String, Object>>> paymentWebhookEvents(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String gateway,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        paymentService.webhookEvents(status, gateway, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, paymentService.webhookEventsCount(status, gateway, search))
    );
  }

  @GetMapping("/admin/refunds")
  public ApiResponse<List<Map<String, Object>>> refunds(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        paymentService.refunds(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, paymentService.refundsCount(status, search))
    );
  }

  private int safePage(int page) {
    return Math.max(1, page);
  }

  private int safePageSize(int pageSize) {
    if (pageSize <= 0) return 100;
    return Math.min(pageSize, 200);
  }

  private int offset(int page, int pageSize) {
    return Math.max(0, (page - 1) * pageSize);
  }
}
