package com.example.tutorplatform.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tutorplatform.common.BusinessException;
import org.junit.jupiter.api.Test;

class StatusTransitionPolicyTest {
  private final StatusTransitionPolicy policy = new StatusTransitionPolicy();

  @Test
  void learningRequestSupportsProposalDrivenFlow() {
    assertThatCode(() -> policy.requireLearningRequest("proposal_received", "waiting_parent_confirmation"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.requireLearningRequest("waiting_parent_confirmation", "matched"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.requireLearningRequest("trial_completed", "converted_to_class"))
        .doesNotThrowAnyException();
  }

  @Test
  void bookingSupportsConfirmationAndCanonicalConversionStatuses() {
    assertThatCode(() -> policy.requireBooking("requested", "parent_confirmed"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.requireBooking("parent_confirmed", "scheduled"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.requireBooking("completed", "converted_to_class"))
        .doesNotThrowAnyException();
  }

  @Test
  void tutorRejectedCannotBeApprovedWithoutResubmission() {
    assertThatThrownBy(() -> policy.requireTutor("rejected", "approved"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Không thể chuyển tutor");
  }
}
