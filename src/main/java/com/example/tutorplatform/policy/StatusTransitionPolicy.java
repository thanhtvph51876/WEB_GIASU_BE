package com.example.tutorplatform.policy;

import com.example.tutorplatform.common.BusinessException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class StatusTransitionPolicy {
  private static final Map<String, Set<String>> LEARNING_REQUEST = Map.of(
      "new", Set.of("consulting", "matched", "cancelled"),
      "consulting", Set.of("matched", "cancelled"),
      "matched", Set.of("trial_scheduled", "rematch", "cancelled"),
      "trial_scheduled", Set.of("trial_completed", "rematch", "cancelled"),
      "trial_completed", Set.of("active", "rematch", "cancelled"),
      "active", Set.of("completed", "cancelled"),
      "rematch", Set.of("matched", "cancelled")
  );

  private static final Map<String, Set<String>> BOOKING = Map.of(
      "pending", Set.of("assigned", "accepted", "scheduled", "cancelled", "expired"),
      "assigned", Set.of("accepted", "rejected", "scheduled", "cancelled"),
      "accepted", Set.of("scheduled", "cancelled"),
      "scheduled", Set.of("completed", "no_show_student", "no_show_tutor", "cancelled"),
      "completed", Set.of("converted", "cancelled"),
      "rejected", Set.of("assigned")
  );

  private static final Map<String, Set<String>> CLASS = Map.of(
      "trial", Set.of("active", "cancelled"),
      "active", Set.of("paused", "completed", "cancelled"),
      "paused", Set.of("active", "cancelled")
  );

  private static final Map<String, Set<String>> SESSION = Map.of(
      "scheduled", Set.of("completed", "cancelled", "student_absent", "tutor_absent"),
      "upcoming", Set.of("completed", "cancelled", "student_absent", "tutor_absent")
  );

  private static final Map<String, Set<String>> PAYMENT = Map.of(
      "pending", Set.of("processing", "paid", "failed", "expired", "cancelled"),
      "processing", Set.of("paid", "failed", "expired"),
      "paid", Set.of("refunded", "partially_refunded"),
      "failed", Set.of("pending"),
      "expired", Set.of("pending"),
      "partially_refunded", Set.of("refunded")
  );

  private static final Map<String, Set<String>> PAYOUT = Map.of(
      "pending", Set.of("processing", "approved", "paid", "completed", "rejected"),
      "approved", Set.of("paid", "rejected"),
      "processing", Set.of("paid", "completed", "rejected")
  );

  private static final Map<String, Set<String>> TUTOR = Map.of(
      "draft", Set.of("pending"),
      "pending", Set.of("approved", "rejected", "need_update"),
      "need_update", Set.of("pending", "rejected"),
      "rejected", Set.of("pending"),
      "approved", Set.of("suspended", "inactive"),
      "suspended", Set.of("approved", "inactive"),
      "inactive", Set.of("approved")
  );

  public void requireLearningRequest(String current, String next) {
    require("learningRequest", LEARNING_REQUEST, current, next);
  }

  public void requireBooking(String current, String next) {
    require("booking", BOOKING, current, next);
  }

  public void requireClass(String current, String next) {
    require("class", CLASS, current, next);
  }

  public void requireSession(String current, String next) {
    require("session", SESSION, current, next);
  }

  public void requirePayment(String current, String next) {
    require("payment", PAYMENT, current, next);
  }

  public void requirePayout(String current, String next) {
    require("payout", PAYOUT, current, next);
  }

  public void requireTutor(String current, String next) {
    require("tutor", TUTOR, current, next);
  }

  private void require(String entity, Map<String, Set<String>> rules, String current, String next) {
    if (current == null || next == null) return;
    if (!rules.getOrDefault(current, Set.of()).contains(next)) {
      throw new BusinessException("INVALID_STATUS_TRANSITION", "Không thể chuyển " + entity + " từ " + current + " sang " + next + ".");
    }
  }
}
