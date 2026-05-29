package com.example.tutorplatform.security;

import com.example.tutorplatform.common.ForbiddenException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
  private static final Set<String> ADMIN_ROLES = Set.of(
      "admin",
      "finance_admin",
      "tutor_admin",
      "support_admin",
      "verification_admin",
      "system_admin"
  );

  private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.ofEntries(
      Map.entry("admin", Set.of("*")),
      Map.entry("system_admin", Set.of("*")),
      Map.entry("finance_admin", Set.of(
          "payments.*",
          "payouts.*",
          "reports.read",
          "operations.read"
      )),
      Map.entry("tutor_admin", Set.of(
          "users.read",
          "tutors.read",
          "tutors.approve",
          "tutors.reject",
          "tutors.request_more_documents",
          "tutor_documents.review",
          "verifications.read",
          "verifications.review",
          "verifications.approve_document",
          "files.view_tutor_document",
          "files.view_verification",
          "learning_requests.*",
          "matching.manage",
          "bookings.*",
          "classes.*",
          "reports.read",
          "operations.read"
      )),
      Map.entry("support_admin", Set.of(
          "users.read",
          "learning_requests.read",
          "bookings.read",
          "classes.read",
          "conversations.read",
          "notifications.*",
          "contact_requests.manage",
          "reviews.read",
          "reports.read",
          "operations.read"
      )),
      Map.entry("verification_admin", Set.of(
          "verifications.*",
          "files.view_verification",
          "reports.read",
          "operations.read"
      ))
  );

  public boolean has(String permission) {
    return SecurityUtils.currentRoles().stream().anyMatch(role -> roleHas(role, permission));
  }

  public boolean hasAny(String... permissions) {
    for (String permission : permissions) {
      if (has(permission)) return true;
    }
    return false;
  }

  public void require(String permission) {
    if (!has(permission)) {
      throw new ForbiddenException("Bạn không có quyền thực hiện thao tác này: " + permission + ".");
    }
  }

  public boolean hasAnyAdminRole() {
    return SecurityUtils.currentRoles().stream().anyMatch(PermissionService::isAdminRole);
  }

  public boolean canAccessAdminArea() {
    return hasAnyAdminRole();
  }

  public static boolean isAdminRole(String role) {
    return role != null && ADMIN_ROLES.contains(normalize(role));
  }

  public static boolean roleHas(String role, String permission) {
    return permissionsForRole(role).stream().anyMatch(grant -> matches(grant, permission));
  }

  public static Set<String> permissionsForRole(String role) {
    return ROLE_PERMISSIONS.getOrDefault(normalize(role), Set.of());
  }

  private static boolean matches(String grant, String permission) {
    if ("*".equals(grant)) return true;
    if (grant.equals(permission)) return true;
    if (grant.endsWith(".*")) {
      return permission.startsWith(grant.substring(0, grant.length() - 1));
    }
    return false;
  }

  private static String normalize(String role) {
    return role == null ? "" : role.toLowerCase(Locale.ROOT);
  }
}
