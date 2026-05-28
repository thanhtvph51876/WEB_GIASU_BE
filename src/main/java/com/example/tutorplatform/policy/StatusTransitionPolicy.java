package com.example.tutorplatform.policy;

import com.example.tutorplatform.common.BusinessException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class StatusTransitionPolicy {
  private static final Map<String, Set<String>> LEARNING_REQUEST = Map.ofEntries(
      Map.entry("draft", Set.of("submitted", "cancelled")),
      Map.entry("submitted", Set.of("matching", "waiting_tutor_proposal", "proposal_received", "matched", "cancelled", "expired")),
      Map.entry("new", Set.of("consulting", "matching", "waiting_tutor_proposal", "proposal_received", "matched", "cancelled", "expired", "closed")),
      Map.entry("consulting", Set.of("matching", "proposal_received", "matched", "cancelled", "expired", "closed")),
      Map.entry("matching", Set.of("waiting_tutor_proposal", "proposal_received", "matched", "rematch", "cancelled", "expired", "closed")),
      Map.entry("waiting_tutor_proposal", Set.of("proposal_received", "waiting_parent_confirmation", "matched", "rematch", "cancelled", "expired", "closed")),
      Map.entry("proposal_received", Set.of("waiting_parent_confirmation", "matched", "rematch", "cancelled", "expired", "closed")),
      Map.entry("waiting_parent_confirmation", Set.of("matched", "trial_scheduled", "rematch", "cancelled", "expired", "closed")),
      Map.entry("matched", Set.of("trial_scheduled", "rematch", "cancelled", "expired", "closed")),
      Map.entry("trial_scheduled", Set.of("trial_completed", "rematch", "cancelled")),
      Map.entry("trial_completed", Set.of("active", "converted_to_class", "rematch", "cancelled", "closed")),
      Map.entry("active", Set.of("completed", "cancelled", "closed")),
      Map.entry("rematch", Set.of("matching", "waiting_tutor_proposal", "matched", "cancelled", "expired", "closed")),
      Map.entry("converted_to_class", Set.of("completed", "closed"))
  );

  private static final Map<String, Set<String>> BOOKING = Map.ofEntries(
      Map.entry("requested", Set.of("parent_confirmed", "tutor_confirmed", "scheduled", "reschedule_requested", "cancelled_by_parent", "cancelled_by_tutor", "cancelled", "expired")),
      Map.entry("parent_confirmed", Set.of("tutor_confirmed", "scheduled", "reschedule_requested", "cancelled_by_parent", "cancelled_by_tutor", "cancelled", "expired")),
      Map.entry("tutor_confirmed", Set.of("parent_confirmed", "scheduled", "reschedule_requested", "cancelled_by_parent", "cancelled_by_tutor", "cancelled", "expired")),
      Map.entry("reschedule_requested", Set.of("parent_confirmed", "tutor_confirmed", "scheduled", "cancelled_by_parent", "cancelled_by_tutor", "cancelled", "expired")),
      Map.entry("pending", Set.of("assigned", "accepted", "scheduled", "cancelled", "expired")),
      Map.entry("assigned", Set.of("accepted", "rejected", "scheduled", "cancelled")),
      Map.entry("accepted", Set.of("scheduled", "cancelled")),
      Map.entry("scheduled", Set.of("completed", "no_show_student", "no_show_parent", "no_show_tutor", "cancelled_by_parent", "cancelled_by_tutor", "cancelled")),
      Map.entry("completed", Set.of("converted", "converted_to_class", "rejected_after_trial", "cancelled")),
      Map.entry("rejected", Set.of("assigned")),
      Map.entry("cancelled_by_parent", Set.of("reschedule_requested")),
      Map.entry("cancelled_by_tutor", Set.of("reschedule_requested"))
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
