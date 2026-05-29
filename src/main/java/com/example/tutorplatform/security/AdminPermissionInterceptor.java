package com.example.tutorplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminPermissionInterceptor implements HandlerInterceptor {
  private static final String ADMIN_PREFIX = "/api/v1/admin";

  private final PermissionService permissions;

  public AdminPermissionInterceptor(PermissionService permissions) {
    this.permissions = permissions;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    if (!path.startsWith(ADMIN_PREFIX) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    permissions.require(requiredPermission(request.getMethod(), path));
    return true;
  }

  private String requiredPermission(String method, String path) {
    boolean read = "GET".equalsIgnoreCase(method);

    if (path.startsWith(ADMIN_PREFIX + "/payment-transactions")
        || path.startsWith(ADMIN_PREFIX + "/payment-webhook-events")
        || path.startsWith(ADMIN_PREFIX + "/refunds")) {
      return "payments.read";
    }
    if (path.startsWith(ADMIN_PREFIX + "/payments/") && path.endsWith("/mark-paid")) return "payments.mark_paid";
    if (path.startsWith(ADMIN_PREFIX + "/payments/") && path.endsWith("/mark-failed")) return "payments.mark_failed";
    if (path.startsWith(ADMIN_PREFIX + "/payments/") && path.endsWith("/refund")) return "payments.refund";
    if (path.equals(ADMIN_PREFIX + "/payments") || path.startsWith(ADMIN_PREFIX + "/payments/")) return "payments.read";

    if (path.startsWith(ADMIN_PREFIX + "/payouts/") && path.endsWith("/approve")) return "payouts.approve";
    if (path.startsWith(ADMIN_PREFIX + "/payouts/") && path.endsWith("/reject")) return "payouts.reject";
    if (path.equals(ADMIN_PREFIX + "/payouts") || path.startsWith(ADMIN_PREFIX + "/payouts/")) return "payouts.read";

    if (path.startsWith(ADMIN_PREFIX + "/verifications/") && path.endsWith("/approve")) return "verifications.approve_document";
    if (path.startsWith(ADMIN_PREFIX + "/verifications/") && !read) return "verifications.review";
    if (path.equals(ADMIN_PREFIX + "/verifications") || path.startsWith(ADMIN_PREFIX + "/verifications/")) return "verifications.read";

    if (path.startsWith(ADMIN_PREFIX + "/tutor-documents/")) return "tutor_documents.review";
    if (path.startsWith(ADMIN_PREFIX + "/tutors/") && (path.endsWith("/suspend") || path.endsWith("/reactivate"))) return "tutors.suspend";
    if (path.startsWith(ADMIN_PREFIX + "/tutors/") && path.endsWith("/approve")) return "tutors.approve";
    if (path.startsWith(ADMIN_PREFIX + "/tutors/") && path.endsWith("/reject")) return "tutors.reject";
    if (path.startsWith(ADMIN_PREFIX + "/tutors/") && path.endsWith("/request-update")) return "tutors.request_more_documents";
    if (path.startsWith(ADMIN_PREFIX + "/tutors/") && !read) return "tutors.manage";
    if (path.equals(ADMIN_PREFIX + "/tutors") || path.startsWith(ADMIN_PREFIX + "/tutors/")) return "tutors.read";

    if (path.equals(ADMIN_PREFIX + "/users")
        || path.startsWith(ADMIN_PREFIX + "/users/")
        || path.equals(ADMIN_PREFIX + "/student-profiles")
        || path.equals(ADMIN_PREFIX + "/parent-profiles")) {
      return read ? "users.read" : "users.manage";
    }

    if (path.startsWith(ADMIN_PREFIX + "/learning-requests/") && (path.endsWith("/matching-tutors") || path.endsWith("/rematch"))) return "matching.manage";
    if (path.equals(ADMIN_PREFIX + "/learning-requests") || path.startsWith(ADMIN_PREFIX + "/learning-requests/")) return read ? "learning_requests.read" : "learning_requests.manage";

    if (path.equals(ADMIN_PREFIX + "/bookings") || path.startsWith(ADMIN_PREFIX + "/bookings/")) return read ? "bookings.read" : "bookings.manage";
    if (path.equals(ADMIN_PREFIX + "/classes") || path.startsWith(ADMIN_PREFIX + "/classes/")
        || path.equals(ADMIN_PREFIX + "/sessions") || path.startsWith(ADMIN_PREFIX + "/sessions/")) {
      return read ? "classes.read" : "classes.manage";
    }

    if (path.equals(ADMIN_PREFIX + "/reviews") || path.startsWith(ADMIN_PREFIX + "/reviews/")) return read ? "reviews.read" : "reviews.manage";
    if (path.equals(ADMIN_PREFIX + "/conversations") || path.startsWith(ADMIN_PREFIX + "/conversations/")) return "conversations.read";
    if (path.startsWith(ADMIN_PREFIX + "/notifications/send")) return "notifications.send";
    if (path.equals(ADMIN_PREFIX + "/notifications") || path.startsWith(ADMIN_PREFIX + "/notifications/")) return "notifications.read";

    if (path.startsWith(ADMIN_PREFIX + "/operations") || path.equals(ADMIN_PREFIX + "/disputes")) return "operations.read";
    if (path.startsWith(ADMIN_PREFIX + "/reports")) return "reports.read";
    if (path.equals(ADMIN_PREFIX + "/settings") || path.startsWith(ADMIN_PREFIX + "/system-settings")) return read ? "settings.read" : "settings.update";
    if (path.startsWith(ADMIN_PREFIX + "/master-data")) return read ? "master_data.read" : "master_data.manage";
    if (path.startsWith(ADMIN_PREFIX + "/contact-requests")) return "contact_requests.manage";
    if (path.startsWith(ADMIN_PREFIX + "/audit-logs")) return "audit.read";

    return "admin.full_access";
  }
}
