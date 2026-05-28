package com.example.tutorplatform.proposal;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.db.DbService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TutorProposalService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TutorProposalService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public List<Map<String, Object>> tutorLeads() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    ensureApprovedTutor(tutorId);
    return jdbc.query("""
        select lr.*, s.name subject_name, gl.name grade_name,
          tp.status proposal_status, tp.id proposal_id
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        left join tutor_proposals tp on tp.learning_request_id = lr.id and tp.tutor_id = ?
        where lr.status not in ('cancelled','completed','closed','converted_to_class','expired')
          and (lr.assigned_tutor_id = ? or lr.public_visible = true or exists (
            select 1 from tutor_subjects ts where ts.tutor_id = ? and ts.subject_id = lr.subject_id
          ))
        order by lr.created_at desc limit 200
        """, this::mapAny, tutorId, tutorId, tutorId);
  }

  public Map<String, Object> tutorLead(UUID requestId) {
    return tutorLeads().stream().filter(item -> requestId.toString().equals(item.get("id"))).findFirst()
        .orElseThrow(() -> new ForbiddenException("Bạn không có quyền xem lead này."));
  }

  public List<Map<String, Object>> proposalsForTutor() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return jdbc.query(proposalSelect() + " where tp.tutor_id = ? order by tp.created_at desc", this::mapProposal, tutorId);
  }

  public List<Map<String, Object>> proposalsForParent() {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query(proposalSelect() + """
        where lr.requester_id = ? or exists (
          select 1 from guardian_student_links gsl
          where gsl.student_profile_id = lr.student_profile_id and gsl.guardian_user_id = ? and gsl.can_book = true
        )
        order by tp.created_at desc
        """, this::mapProposal, userId, userId);
  }

  @Transactional
  public Map<String, Object> sendProposal(UUID requestId, Map<String, Object> body) throws Exception {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    ensureApprovedTutor(tutorId);
    tutorLead(requestId);
    int fee = number(body.get("proposedFee"));
    if (fee < 0) throw new BusinessException("INVALID_PROPOSAL_FEE", "Học phí đề xuất không hợp lệ.");
    String feeUnit = value(body, "feeUnit", "PER_SESSION").toUpperCase();
    if (!List.of("PER_SESSION", "PER_HOUR", "PER_MONTH").contains(feeUnit)) throw new BusinessException("INVALID_FEE_UNIT", "Đơn vị học phí không hợp lệ.");
    String teachingMode = value(body, "teachingMode", "ONLINE").toUpperCase();
    if (!List.of("ONLINE", "OFFLINE", "HYBRID").contains(teachingMode)) throw new BusinessException("INVALID_TEACHING_MODE", "Hình thức học không hợp lệ.");
    UUID proposalId = jdbc.queryForObject("""
        insert into tutor_proposals(learning_request_id, tutor_id, proposed_fee, fee_unit, teaching_mode,
          available_slots, proposed_start_date, teaching_plan, relevant_experience, expected_outcome,
          message_to_parent, trial_session_type, trial_fee, status, expires_at)
        values (?, ?, ?, ?, ?, ?::jsonb, ?::date, ?, ?, ?, ?, ?, ?, 'SENT', now() + interval '7 days')
        on conflict(learning_request_id, tutor_id) do update set
          proposed_fee = excluded.proposed_fee,
          fee_unit = excluded.fee_unit,
          teaching_mode = excluded.teaching_mode,
          available_slots = excluded.available_slots,
          proposed_start_date = excluded.proposed_start_date,
          teaching_plan = excluded.teaching_plan,
          relevant_experience = excluded.relevant_experience,
          expected_outcome = excluded.expected_outcome,
          message_to_parent = excluded.message_to_parent,
          trial_session_type = excluded.trial_session_type,
          trial_fee = excluded.trial_fee,
          status = 'SENT',
          expires_at = excluded.expires_at,
          updated_at = now()
        returning id
        """, UUID.class, requestId, tutorId, fee, feeUnit, teachingMode, json(body.getOrDefault("availableSlots", List.of())),
        string(body.get("proposedStartDate")), string(body.get("teachingPlan")), string(body.get("relevantExperience")),
        string(body.get("expectedOutcome")), string(body.get("messageToParent")), string(body.get("trialSessionType")), nullableNumber(body.get("trialFee")));
    history(proposalId, null, "SENT", "tutor_submit", string(body.get("messageToParent")));
    String oldStatus = jdbc.queryForObject("select status from learning_requests where id = ?", String.class, requestId);
    jdbc.update("update learning_requests set status = 'proposal_received', updated_at = now() where id = ? and status not in ('trial_scheduled','trial_completed','active','completed','cancelled','closed','converted_to_class')", requestId);
    learningHistory(requestId, oldStatus, "proposal_received", "proposal_received", "Gia sư gửi proposal.");
    notifyRequestOwners(requestId, proposalId);
    db.auditCurrent("tutor.proposal.send", "tutorProposal", proposalId, "Gia sư gửi proposal cho yêu cầu học.");
    return proposalById(proposalId);
  }

  @Transactional
  public Map<String, Object> updateProposal(UUID proposalId, Map<String, Object> body) throws Exception {
    Map<String, Object> proposal = proposalById(proposalId);
    requireTutorOwner(proposal);
    if (!List.of("SENT", "VIEWED", "SHORTLISTED").contains(proposal.get("status"))) throw new BusinessException("PROPOSAL_NOT_EDITABLE", "Proposal hiện không thể chỉnh sửa.");
    jdbc.update("""
        update tutor_proposals set proposed_fee = coalesce(?, proposed_fee), fee_unit = coalesce(?, fee_unit),
          teaching_mode = coalesce(?, teaching_mode), available_slots = coalesce(?::jsonb, available_slots),
          teaching_plan = coalesce(?, teaching_plan), expected_outcome = coalesce(?, expected_outcome),
          message_to_parent = coalesce(?, message_to_parent), trial_fee = coalesce(?, trial_fee), updated_at = now()
        where id = ?
        """, nullableNumber(body.get("proposedFee")), string(body.get("feeUnit")), string(body.get("teachingMode")),
        body.containsKey("availableSlots") ? json(body.get("availableSlots")) : null, string(body.get("teachingPlan")),
        string(body.get("expectedOutcome")), string(body.get("messageToParent")), nullableNumber(body.get("trialFee")), proposalId);
    db.auditCurrent("tutor.proposal.update", "tutorProposal", proposalId, "Gia sư cập nhật proposal.");
    return proposalById(proposalId);
  }

  @Transactional
  public Map<String, Object> withdrawProposal(UUID proposalId) {
    Map<String, Object> proposal = proposalById(proposalId);
    requireTutorOwner(proposal);
    changeProposalStatus(proposalId, proposal.get("status").toString(), "WITHDRAWN", "withdraw", null);
    db.auditCurrent("tutor.proposal.withdraw", "tutorProposal", proposalId, "Gia sư rút proposal.");
    return proposalById(proposalId);
  }

  @Transactional
  public Map<String, Object> acceptProposal(UUID proposalId, String note) {
    Map<String, Object> proposal = proposalById(proposalId);
    requireRequestOwner(proposal);
    if (!List.of("SENT", "VIEWED", "SHORTLISTED").contains(proposal.get("status"))) {
      throw new BusinessException("PROPOSAL_NOT_ACCEPTABLE", "Proposal hiện không thể chấp nhận.");
    }
    changeProposalStatus(proposalId, proposal.get("status").toString(), "ACCEPTED", "parent_accept", note);
    UUID requestId = UUID.fromString(proposal.get("learningRequestId").toString());
    jdbc.update("update tutor_proposals set status = 'REJECTED', updated_at = now() where learning_request_id = ? and id <> ? and status in ('SENT','VIEWED','SHORTLISTED')", requestId, proposalId);
    String oldStatus = jdbc.queryForObject("select status from learning_requests where id = ?", String.class, requestId);
    jdbc.update("update learning_requests set assigned_tutor_id = ?, status = 'waiting_parent_confirmation', updated_at = now() where id = ?", UUID.fromString(proposal.get("tutorId").toString()), requestId);
    learningHistory(requestId, oldStatus, "waiting_parent_confirmation", "parent_accept_proposal", note);
    db.auditCurrent("parent.proposal.accept", "tutorProposal", proposalId, "Phụ huynh/học sinh chấp nhận proposal.");
    return proposalById(proposalId);
  }

  @Transactional
  public Map<String, Object> rejectProposal(UUID proposalId, String reason) {
    Map<String, Object> proposal = proposalById(proposalId);
    requireRequestOwner(proposal);
    changeProposalStatus(proposalId, proposal.get("status").toString(), "REJECTED", "parent_reject", reason);
    db.auditCurrent("parent.proposal.reject", "tutorProposal", proposalId, "Phụ huynh/học sinh từ chối proposal.");
    return proposalById(proposalId);
  }

  public Map<String, Object> proposalById(UUID proposalId) {
    return jdbc.query(proposalSelect() + " where tp.id = ?", this::mapProposal, proposalId).stream()
        .findFirst().orElseThrow(() -> new NotFoundException("Không tìm thấy proposal."));
  }

  public void requireRequestOwner(Map<String, Object> proposal) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    Object requester = proposal.get("requesterId");
    if (requester != null && current.equals(UUID.fromString(requester.toString()))) return;
    Object studentProfile = proposal.get("studentProfileId");
    if (studentProfile != null) {
      Integer count = jdbc.queryForObject("""
          select count(*) from guardian_student_links
          where guardian_user_id = ? and student_profile_id = ? and can_book = true
          """, Integer.class, current, UUID.fromString(studentProfile.toString()));
      if (count != null && count > 0) return;
    }
    throw new ForbiddenException("Bạn không có quyền thao tác proposal này.");
  }

  private void requireTutorOwner(Map<String, Object> proposal) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    if (!tutorId.equals(UUID.fromString(proposal.get("tutorId").toString()))) throw new ForbiddenException("Bạn không có quyền thao tác proposal này.");
  }

  private void ensureApprovedTutor(UUID tutorId) {
    String status = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(status)) throw new BusinessException("TUTOR_NOT_APPROVED", "Gia sư phải được duyệt mới được gửi proposal.");
  }

  private void changeProposalStatus(UUID proposalId, String oldStatus, String newStatus, String reason, String note) {
    jdbc.update("update tutor_proposals set status = ?, updated_at = now() where id = ?", newStatus, proposalId);
    history(proposalId, oldStatus, newStatus, reason, note);
  }

  private void history(UUID proposalId, String oldStatus, String newStatus, String reason, String note) {
    Map<String, Object> user = db.currentUserOrThrow();
    jdbc.update("""
        insert into tutor_proposal_status_history(tutor_proposal_id, actor_user_id, actor_role, old_status, new_status, reason, note)
        values (?, ?, ?, ?, ?, ?, ?)
        """, proposalId, UUID.fromString(user.get("id").toString()), user.get("role"), oldStatus, newStatus, reason, note);
  }

  private void learningHistory(UUID requestId, String oldStatus, String newStatus, String reason, String note) {
    Map<String, Object> user = db.currentUserOrThrow();
    jdbc.update("""
        insert into learning_request_status_history(learning_request_id, actor_user_id, actor_role, old_status, new_status, reason, note)
        values (?, ?, ?, ?, ?, ?, ?)
        """, requestId, UUID.fromString(user.get("id").toString()), user.get("role"), oldStatus, newStatus, reason, note);
  }

  private void notifyRequestOwners(UUID requestId, UUID proposalId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    Object requesterId = request.get("userId");
    if (requesterId != null) {
      db.notify(UUID.fromString(requesterId.toString()), "info", "Gia sư gửi proposal", "Bạn có proposal mới cho yêu cầu học.", "/dashboard/parent/proposals", "tutorProposal", proposalId);
    }
    Object studentProfileId = request.get("studentProfileId");
    if (studentProfileId != null) {
      List<UUID> guardians = jdbc.query("select guardian_user_id from guardian_student_links where student_profile_id = ? and can_book = true", (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(studentProfileId.toString()));
      for (UUID guardian : guardians) {
        db.notify(guardian, "info", "Gia sư gửi proposal", "Bạn có proposal mới cho học sinh.", "/dashboard/parent/proposals", "tutorProposal", proposalId);
      }
    }
  }

  private String proposalSelect() {
    return """
        select tp.*, lr.requester_id, lr.student_profile_id, lr.request_code, lr.student_name, lr.parent_name,
               lr.phone, lr.email, lr.status request_status, s.name subject_name, gl.name grade_name,
               u.full_name tutor_name, u.avatar_url tutor_avatar
        from tutor_proposals tp
        join learning_requests lr on lr.id = tp.learning_request_id
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        join tutor_profiles tprof on tprof.id = tp.tutor_id
        join users u on u.id = tprof.user_id
        """;
  }

  private Map<String, Object> mapProposal(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = mapAny(rs, row);
    m.put("learningRequestId", rs.getObject("learning_request_id").toString());
    m.put("tutorId", rs.getObject("tutor_id").toString());
    m.put("subject", rs.getString("subject_name"));
    m.put("grade", rs.getString("grade_name"));
    return m;
  }

  private Map<String, Object> mapAny(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
      String key = camel(rs.getMetaData().getColumnLabel(i));
      Object value = rs.getObject(i);
      if (value instanceof UUID uuid) value = uuid.toString();
      if (value instanceof OffsetDateTime time) value = time.toString();
      m.put(key, value);
    }
    return m;
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value == null ? List.of() : value);
  }

  private String string(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  private String value(Map<String, Object> body, String key, String fallback) {
    String value = string(body.get(key));
    return value == null ? fallback : value;
  }

  private int number(Object value) {
    if (value instanceof Number number) return number.intValue();
    if (value == null || value.toString().isBlank()) return 0;
    return Integer.parseInt(value.toString());
  }

  private Integer nullableNumber(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    return number(value);
  }

  private String camel(String label) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char c : label.toCharArray()) {
      if (c == '_') upper = true;
      else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
      else sb.append(c);
    }
    return sb.toString();
  }
}
