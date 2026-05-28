package com.example.tutorplatform.proposal;

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
@RequestMapping("/api/v1")
public class TutorProposalController {
  private final TutorProposalService service;

  public TutorProposalController(TutorProposalService service) {
    this.service = service;
  }

  @GetMapping("/tutor/proposals")
  public ApiResponse<List<Map<String, Object>>> tutorProposals() {
    return ApiResponse.ok(service.proposalsForTutor());
  }

  @PostMapping("/tutor/leads/{requestId}/proposals")
  public ApiResponse<Map<String, Object>> sendProposal(@PathVariable UUID requestId, @RequestBody Map<String, Object> body) throws Exception {
    return ApiResponse.ok(service.sendProposal(requestId, body), "Đã gửi proposal cho phụ huynh/học sinh.");
  }

  @PatchMapping("/tutor/proposals/{proposalId}")
  public ApiResponse<Map<String, Object>> updateProposal(@PathVariable UUID proposalId, @RequestBody Map<String, Object> body) throws Exception {
    return ApiResponse.ok(service.updateProposal(proposalId, body));
  }

  @PostMapping("/tutor/proposals/{proposalId}/withdraw")
  public ApiResponse<Map<String, Object>> withdrawProposal(@PathVariable UUID proposalId) {
    return ApiResponse.ok(service.withdrawProposal(proposalId), "Đã rút proposal.");
  }

  @GetMapping("/parent/proposals")
  public ApiResponse<List<Map<String, Object>>> parentProposals() {
    return ApiResponse.ok(service.proposalsForParent());
  }

  @PostMapping("/parent/proposals/{proposalId}/accept")
  public ApiResponse<Map<String, Object>> acceptProposal(@PathVariable UUID proposalId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.acceptProposal(proposalId, body == null ? null : String.valueOf(body.getOrDefault("note", ""))), "Đã chấp nhận proposal.");
  }

  @PostMapping("/parent/proposals/{proposalId}/reject")
  public ApiResponse<Map<String, Object>> rejectProposal(@PathVariable UUID proposalId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.rejectProposal(proposalId, body == null ? null : String.valueOf(body.getOrDefault("reason", ""))), "Đã từ chối proposal.");
  }
}
