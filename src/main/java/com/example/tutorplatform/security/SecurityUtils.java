package com.example.tutorplatform.security;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
  private SecurityUtils() {}

  public static Optional<UUID> currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(auth.getName()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  public static boolean hasRole(String role) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_" + role.toUpperCase(Locale.ROOT)));
  }

  public static boolean hasAnyRole(String... roles) {
    for (String role : roles) {
      if (hasRole(role)) return true;
    }
    return false;
  }

  public static Set<String> currentRoles() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) return Set.of();
    return auth.getAuthorities().stream()
        .map(a -> a.getAuthority())
        .filter(a -> a.startsWith("ROLE_"))
        .map(a -> a.substring("ROLE_".length()).toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }
}
