package com.example.tutorplatform.booking;

import com.example.tutorplatform.common.ApiResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trial-bookings")
public class TrialBookingController {
  private final TrialBookingService service;

  public TrialBookingController(TrialBookingService service) {
    this.service = service;
  }

  @PostMapping
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.create(body), "Đã tạo booking học thử từ proposal.");
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> get(@PathVariable UUID id) {
    return ApiResponse.ok(service.get(id));
  }

  @PostMapping("/{id}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.confirm(id, body == null ? Map.of() : body), "Đã xác nhận booking học thử.");
  }

  @PostMapping("/{id}/cancel")
  public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.cancel(id, body == null ? Map.of() : body), "Đã hủy booking học thử.");
  }

  @PostMapping("/{id}/mark-no-show")
  public ApiResponse<Map<String, Object>> markNoShow(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.markNoShow(id, body), "Đã ghi nhận no-show.");
  }

  @PostMapping("/{id}/complete")
  public ApiResponse<Map<String, Object>> complete(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.complete(id, body == null ? Map.of() : body), "Đã hoàn tất học thử.");
  }

  @PostMapping("/{id}/convert-to-class")
  public ApiResponse<Map<String, Object>> convert(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.convertToClass(id, body == null ? Map.of() : body), "Đã chuyển học thử thành lớp.");
  }
}
