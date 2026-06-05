package com.example.tutorplatform.admin;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.PageMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  @GetMapping("/operations/work-items")
  public ApiResponse<List<Map<String, Object>>> workItems() {
    return ApiResponse.ok(service.workItems());
  }

  @GetMapping("/disputes")
  public ApiResponse<List<Map<String, Object>>> disputes(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String sla,
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = Math.max(1, page);
    int safePageSize = pageSize <= 0 ? 100 : Math.min(pageSize, 200);
    return ApiResponse.page(
        service.disputes(search, status, priority, sla, owner, safePageSize, Math.max(0, (safePage - 1) * safePageSize)),
        PageMetadata.of(safePage, safePageSize, service.disputeCount(search, status, priority, sla, owner))
    );
  }

  @GetMapping("/disputes/{id}")
  public ApiResponse<Map<String, Object>> dispute(@PathVariable UUID id) {
    return ApiResponse.ok(service.dispute(id));
  }

  @PatchMapping("/disputes/{id}")
  public ApiResponse<Map<String, Object>> updateDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.updateDispute(id, body));
  }

  @PostMapping("/disputes/{id}/assign")
  public ApiResponse<Map<String, Object>> assignDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.assignDispute(id, body));
  }

  @PostMapping("/disputes/{id}/notes")
  public ApiResponse<Map<String, Object>> addDisputeNote(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.addDisputeNote(id, body));
  }

  @PostMapping("/disputes/{id}/timeline")
  public ApiResponse<Map<String, Object>> addDisputeTimeline(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.addDisputeTimeline(id, body));
  }

  @PostMapping("/disputes/{id}/resolve")
  public ApiResponse<Map<String, Object>> resolveDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.resolveDispute(id, body));
  }

  @PostMapping("/disputes/{id}/close")
  public ApiResponse<Map<String, Object>> closeDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.closeDispute(id, body));
  }

  @PostMapping("/disputes/{id}/escalate")
  public ApiResponse<Map<String, Object>> escalateDispute(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.escalateDispute(id, body));
  }
}
