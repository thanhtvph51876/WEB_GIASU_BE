package com.example.tutorplatform.admin;

import com.example.tutorplatform.common.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOperationController {
  private final AdminOperationService service;

  public AdminOperationController(AdminOperationService service) {
    this.service = service;
  }

  @GetMapping("/operations/overview")
  public ApiResponse<Map<String, Object>> overview() {
    return ApiResponse.ok(service.overview());
  }

  @GetMapping("/operations/matching-queue")
  public ApiResponse<List<Map<String, Object>>> matchingQueue() {
    return ApiResponse.ok(service.matchingQueue());
  }

  @GetMapping("/operations/booking-risk")
  public ApiResponse<List<Map<String, Object>>> bookingRisk() {
    return ApiResponse.ok(service.bookingRisk());
  }

  @GetMapping("/operations/verification-risk")
  public ApiResponse<List<Map<String, Object>>> verificationRisk() {
    return ApiResponse.ok(service.verificationRisk());
  }

  @GetMapping("/operations/payment-reconciliation")
  public ApiResponse<List<Map<String, Object>>> paymentReconciliation() {
    return ApiResponse.ok(service.paymentReconciliation());
  }

  @GetMapping("/operations/payout-queue")
  public ApiResponse<List<Map<String, Object>>> payoutQueue() {
    return ApiResponse.ok(service.payoutQueue());
  }

  @GetMapping("/operations/tutor-quality")
  public ApiResponse<List<Map<String, Object>>> tutorQuality() {
    return ApiResponse.ok(service.tutorQuality());
  }

  @GetMapping("/disputes")
  public ApiResponse<List<Map<String, Object>>> disputes() {
    return ApiResponse.ok(service.disputes());
  }

  @GetMapping("/disputes/{id}")
  public ApiResponse<Map<String, Object>> dispute(@PathVariable UUID id) {
    return ApiResponse.ok(service.dispute(id));
  }

  @PatchMapping("/disputes/{id}")
  public ApiResponse<Map<String, Object>> updateDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.updateDispute(id, body));
  }
}
