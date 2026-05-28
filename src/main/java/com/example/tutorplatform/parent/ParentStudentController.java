package com.example.tutorplatform.parent;

import com.example.tutorplatform.common.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parent")
public class ParentStudentController {
  private final ParentStudentService service;

  public ParentStudentController(ParentStudentService service) {
    this.service = service;
  }

  @PostMapping("/students")
  public ApiResponse<Map<String, Object>> createStudent(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.createStudent(body), "Đã tạo hồ sơ học sinh.");
  }

  @GetMapping("/students")
  public ApiResponse<List<Map<String, Object>>> students() {
    return ApiResponse.ok(service.myStudents());
  }

  @GetMapping("/students/{studentId}")
  public ApiResponse<Map<String, Object>> student(@PathVariable UUID studentId) {
    return ApiResponse.ok(service.studentById(studentId));
  }

  @PatchMapping("/students/{studentId}")
  public ApiResponse<Map<String, Object>> updateStudent(@PathVariable UUID studentId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.updateStudent(studentId, body));
  }

  @GetMapping("/students/{studentId}/dashboard")
  public ApiResponse<Map<String, Object>> dashboard(@PathVariable UUID studentId) {
    return ApiResponse.ok(service.dashboard(studentId));
  }

  @GetMapping("/students/{studentId}/schedule")
  public ApiResponse<List<Map<String, Object>>> schedule(@PathVariable UUID studentId) {
    return ApiResponse.ok(service.schedule(studentId));
  }

  @GetMapping("/students/{studentId}/progress")
  public ApiResponse<Map<String, Object>> progress(@PathVariable UUID studentId) {
    return ApiResponse.ok(service.progress(studentId));
  }

  @GetMapping("/students/{studentId}/payments")
  public ApiResponse<List<Map<String, Object>>> payments(@PathVariable UUID studentId) {
    return ApiResponse.ok(service.payments(studentId));
  }
}
