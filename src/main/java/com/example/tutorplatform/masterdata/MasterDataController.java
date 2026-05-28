package com.example.tutorplatform.masterdata;

import com.example.tutorplatform.common.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {
  private final MasterDataService service;

  public MasterDataController(MasterDataService service) {
    this.service = service;
  }

  @GetMapping("/locations")
  public ApiResponse<List<Map<String, Object>>> locations(@RequestParam(required = false) String type, @RequestParam(required = false) String parentId) {
    return ApiResponse.ok(service.locations(type, service.uuid(parentId), true));
  }

  @GetMapping("/subject-categories")
  public ApiResponse<List<Map<String, Object>>> subjectCategories() {
    return ApiResponse.ok(service.subjectCategories(true));
  }

  @GetMapping("/subjects")
  public ApiResponse<List<Map<String, Object>>> subjects(@RequestParam(required = false) String categoryId, @RequestParam(required = false) String q) {
    return ApiResponse.ok(service.subjects(service.uuid(categoryId), q, true));
  }

  @GetMapping("/education-levels")
  public ApiResponse<List<Map<String, Object>>> educationLevels() {
    return ApiResponse.ok(service.educationLevels(true));
  }

  @GetMapping("/grades")
  public ApiResponse<List<Map<String, Object>>> grades(@RequestParam(required = false) String educationLevelId) {
    UUID id = service.uuid(educationLevelId);
    return ApiResponse.ok(service.grades(id, true));
  }

  @GetMapping("/languages")
  public ApiResponse<List<Map<String, Object>>> languages() {
    return ApiResponse.ok(service.languages(true));
  }

  @GetMapping("/certificates")
  public ApiResponse<List<Map<String, Object>>> certificates() {
    return ApiResponse.ok(service.certificates(true));
  }

  @GetMapping("/teaching-modes")
  public ApiResponse<List<Map<String, Object>>> teachingModes() {
    return ApiResponse.ok(service.teachingModes(true));
  }

  @GetMapping("/cancellation-policies")
  public ApiResponse<List<Map<String, Object>>> cancellationPolicies() {
    return ApiResponse.ok(service.cancellationPolicies(true));
  }
}
