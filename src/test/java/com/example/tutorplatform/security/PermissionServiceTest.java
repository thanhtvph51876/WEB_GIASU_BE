package com.example.tutorplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionServiceTest {
  @Test
  void financeAdminCanManageFinanceButCannotApproveTutors() {
    assertThat(PermissionService.roleHas("finance_admin", "payments.refund")).isTrue();
    assertThat(PermissionService.roleHas("finance_admin", "payouts.approve")).isTrue();
    assertThat(PermissionService.roleHas("finance_admin", "tutors.approve")).isFalse();
    assertThat(PermissionService.roleHas("finance_admin", "crm.manage")).isFalse();
    assertThat(PermissionService.roleHas("finance_admin", "settings.update")).isFalse();
  }

  @Test
  void verificationAdminCanReviewVerificationFilesOnly() {
    assertThat(PermissionService.roleHas("verification_admin", "verifications.review")).isTrue();
    assertThat(PermissionService.roleHas("verification_admin", "files.view_verification")).isTrue();
    assertThat(PermissionService.roleHas("verification_admin", "files.view_tutor_document")).isFalse();
    assertThat(PermissionService.roleHas("verification_admin", "payments.refund")).isFalse();
    assertThat(PermissionService.roleHas("verification_admin", "settings.update")).isFalse();
    assertThat(PermissionService.roleHas("verification_admin", "crm.manage")).isFalse();
  }

  @Test
  void tutorAdminCanApproveTutorAndReviewVerificationButCannotSuspend() {
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.read")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.review")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.approve_document")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "tutors.approve")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "sessions.manage")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "crm.manage")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "payments.refund")).isFalse();
    assertThat(PermissionService.roleHas("tutor_admin", "settings.update")).isFalse();
    assertThat(PermissionService.roleHas("tutor_admin", "tutors.suspend")).isFalse();
  }

  @Test
  void supportAdminCanManageComplaintCasesButCannotTouchFinanceOrSettings() {
    assertThat(PermissionService.roleHas("support_admin", "complaints.manage")).isTrue();
    assertThat(PermissionService.roleHas("support_admin", "sessions.read")).isTrue();
    assertThat(PermissionService.roleHas("support_admin", "crm.manage")).isTrue();
    assertThat(PermissionService.roleHas("support_admin", "payments.refund")).isFalse();
    assertThat(PermissionService.roleHas("support_admin", "payouts.approve")).isFalse();
    assertThat(PermissionService.roleHas("support_admin", "tutors.approve")).isFalse();
    assertThat(PermissionService.roleHas("support_admin", "settings.update")).isFalse();
  }

  @Test
  void systemAdminHasWildcardAccess() {
    assertThat(PermissionService.roleHas("system_admin", "settings.update")).isTrue();
    assertThat(PermissionService.roleHas("system_admin", "admin.full_access")).isTrue();
  }
}
