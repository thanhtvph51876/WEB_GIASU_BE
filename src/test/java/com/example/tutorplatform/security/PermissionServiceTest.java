package com.example.tutorplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionServiceTest {
  @Test
  void financeAdminCanManageFinanceButCannotApproveTutors() {
    assertThat(PermissionService.roleHas("finance_admin", "payments.refund")).isTrue();
    assertThat(PermissionService.roleHas("finance_admin", "payouts.approve")).isTrue();
    assertThat(PermissionService.roleHas("finance_admin", "tutors.approve")).isFalse();
  }

  @Test
  void verificationAdminCanReviewVerificationFilesOnly() {
    assertThat(PermissionService.roleHas("verification_admin", "verifications.review")).isTrue();
    assertThat(PermissionService.roleHas("verification_admin", "files.view_verification")).isTrue();
    assertThat(PermissionService.roleHas("verification_admin", "files.view_tutor_document")).isFalse();
  }

  @Test
  void tutorAdminCanApproveTutorAndReviewVerificationButCannotSuspend() {
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.read")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.review")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "verifications.approve_document")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "tutors.approve")).isTrue();
    assertThat(PermissionService.roleHas("tutor_admin", "tutors.suspend")).isFalse();
  }

  @Test
  void systemAdminHasWildcardAccess() {
    assertThat(PermissionService.roleHas("system_admin", "settings.update")).isTrue();
    assertThat(PermissionService.roleHas("system_admin", "admin.full_access")).isTrue();
  }
}
