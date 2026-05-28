package com.example.tutorplatform.tutor;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.proposal.TutorProposalService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tutor/leads")
public class TutorLeadController {
  private final TutorProposalService service;

  public TutorLeadController(TutorProposalService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> leads() {
    return ApiResponse.ok(service.tutorLeads());
  }

  @GetMapping("/{requestId}")
  public ApiResponse<Map<String, Object>> lead(@PathVariable UUID requestId) {
    return ApiResponse.ok(service.tutorLead(requestId));
  }
}
