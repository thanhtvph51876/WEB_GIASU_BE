package com.example.tutorplatform.dto.learningrequest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record CreateLearningRequestRequest(
    @NotBlank String studentName,
    String parentName,
    String phone,
    @Email String email,
    String grade,
    @NotBlank String subject,
    String subjectId,
    String gradeLevelId,
    String goal,
    String teachingMode,
    String learningMode,
    String province,
    String district,
    @Min(0) Integer budgetMin,
    @Min(0) Integer budgetMax,
    @Min(0) Integer expectedFee,
    String preferredSchedule,
    String learningGoal,
    String note
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "studentName", studentName);
    put(body, "parentName", parentName);
    put(body, "phone", phone);
    put(body, "email", email);
    put(body, "grade", grade);
    put(body, "subject", subject);
    put(body, "subjectId", subjectId);
    put(body, "gradeLevelId", gradeLevelId);
    put(body, "goal", goal);
    put(body, "teachingMode", teachingMode);
    put(body, "learningMode", learningMode);
    put(body, "province", province);
    put(body, "district", district);
    put(body, "budgetMin", budgetMin);
    put(body, "budgetMax", budgetMax);
    put(body, "expectedFee", expectedFee);
    put(body, "preferredSchedule", preferredSchedule);
    put(body, "learningGoal", learningGoal);
    put(body, "note", note);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
