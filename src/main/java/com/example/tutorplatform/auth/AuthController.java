package com.example.tutorplatform.auth;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.config.AppProperties;
import com.example.tutorplatform.db.DbService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final RefreshTokenService refreshTokenService;
  private final AppProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthController(DbService db, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService, AppProperties properties) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.passwordEncoder = passwordEncoder;
    this.refreshTokenService = refreshTokenService;
    this.properties = properties;
  }

  @PostMapping("/register")
  @Transactional
  public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
    if (!request.role().matches("student|parent|tutor")) {
      throw new BusinessException("INVALID_ROLE", "Vai trò đăng ký không hợp lệ.");
    }
    if (db.userByEmail(request.email()).isPresent()) {
      throw new BusinessException("EMAIL_EXISTS", "Email đã được sử dụng.");
    }

    UUID userId = jdbc.queryForObject("""
        insert into users(email, password_hash, full_name, phone, role, status, email_verified)
        values (?, ?, ?, ?, ?, 'active', false)
        returning id
        """, UUID.class, request.email().trim().toLowerCase(), passwordEncoder.encode(request.password()),
        request.fullName().trim(), request.phone(), request.role());

    if ("student".equals(request.role())) {
      jdbc.update("insert into student_profiles(user_id, preferred_learning_mode) values (?, 'both')", userId);
    } else if ("parent".equals(request.role())) {
      jdbc.update("insert into parent_profiles(user_id, relationship_to_student) values (?, 'parent')", userId);
    } else if ("tutor".equals(request.role())) {
      jdbc.update("""
          insert into tutor_profiles(user_id, headline, bio, gender, university, major, status)
          values (?, '', '', 'other', '', '', 'draft')
          """, userId);
    }

    Map<String, Object> user = db.userById(userId).orElseThrow();
    Map<String, Object> verification = createEmailVerificationToken(userId, request.email(), servletRequest);
    db.audit(userId, request.role(), "auth.register", "user", userId, "Người dùng đăng ký tài khoản mới.");
    Map<String, Object> payload = authPayload(user, servletRequest);
    payload.put("emailVerificationRequired", true);
    includeDevToken(payload, "emailVerification", verification);
    return ApiResponse.ok(payload, "Đăng ký thành công");
  }

  @PostMapping("/login")
  public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    Map<String, Object> raw = db.optional("select * from users where lower(email)=lower(?)", (rs, row) -> {
      Map<String, Object> user = db.userMapper().mapRow(rs, row);
      user.put("passwordHash", rs.getString("password_hash"));
      return user;
    }, request.email()).orElseThrow(() ->
        new BusinessException("INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.", HttpStatus.UNAUTHORIZED));
    if (!passwordEncoder.matches(request.password(), raw.get("passwordHash").toString())) {
      throw new BusinessException("INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.", HttpStatus.UNAUTHORIZED);
    }
    if ("inactive".equals(raw.get("status")) || "suspended".equals(raw.get("status"))) {
      throw new ForbiddenException("Tài khoản đã bị khóa.");
    }
    UUID userId = UUID.fromString(raw.get("id").toString());
    jdbc.update("update users set last_login_at = now(), updated_at = now() where id = ?", userId);
    Map<String, Object> user = db.userById(userId).orElseThrow();
    return ApiResponse.ok(authPayload(user, servletRequest), "Đăng nhập thành công");
  }

  @PostMapping("/refresh")
  public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest servletRequest) {
    RefreshTokenService.TokenPair pair = refreshTokenService.rotate(request.refreshToken(), ip(servletRequest), userAgent(servletRequest));
    return ApiResponse.ok(tokenPayload(pair));
  }

  @PostMapping("/logout")
  public ApiResponse<Map<String, Object>> logout(@RequestBody(required = false) RefreshTokenRequest request) {
    if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
      refreshTokenService.revoke(request.refreshToken());
    } else {
      refreshTokenService.revokeAllForUser(db.currentUserIdOrThrow(), "Người dùng đăng xuất và thu hồi refresh token hiện có.");
    }
    return ApiResponse.ok(Map.of("loggedOut", true), "Đã đăng xuất");
  }

  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me() {
    return ApiResponse.ok(db.currentUserOrThrow());
  }

  @PostMapping("/forgot-password")
  @Transactional
  public ApiResponse<Map<String, Object>> forgotPassword(@RequestBody Map<String, Object> body, HttpServletRequest servletRequest) {
    String email = firstString(body, "email");
    if (email == null) {
      throw new BusinessException("EMAIL_REQUIRED", "Vui lòng nhập email.");
    }
    String normalizedEmail = email.trim().toLowerCase();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    db.userByEmail(normalizedEmail).ifPresent(user -> {
      UUID userId = UUID.fromString(user.get("id").toString());
      Map<String, Object> reset = createPasswordResetToken(userId, normalizedEmail, servletRequest);
      includeDevToken(response, "passwordReset", reset);
      db.audit(userId, user.get("role").toString(), "auth.password_reset_requested", "user", userId, "Người dùng yêu cầu đặt lại mật khẩu.");
    });
    return ApiResponse.ok(response, "Nếu email tồn tại, hệ thống đã tạo hướng dẫn đặt lại mật khẩu.");
  }

  @PostMapping("/reset-password")
  @Transactional
  public ApiResponse<Map<String, Object>> resetPassword(@RequestBody Map<String, Object> body) {
    String token = firstString(body, "token");
    String newPassword = firstString(body, "newPassword", "password");
    if (token == null) {
      throw new BusinessException("TOKEN_REQUIRED", "Token đặt lại mật khẩu là bắt buộc.");
    }
    if (newPassword == null || newPassword.length() < 8) {
      throw new BusinessException("WEAK_PASSWORD", "Mật khẩu mới tối thiểu 8 ký tự.");
    }
    Map<String, Object> row = validPasswordResetToken(token);
    UUID userId = (UUID) row.get("userId");
    jdbc.update("update users set password_hash = ?, updated_at = now() where id = ?", passwordEncoder.encode(newPassword), userId);
    jdbc.update("update password_reset_tokens set used_at = now() where id = ?", row.get("id"));
    refreshTokenService.revokeAllForUser(userId, "Mật khẩu đã được đặt lại, thu hồi toàn bộ refresh token cũ.");
    db.userById(userId).ifPresent(user ->
        db.audit(userId, user.get("role").toString(), "auth.password_reset_completed", "user", userId, "Người dùng đặt lại mật khẩu thành công."));
    return ApiResponse.ok(Map.of("accepted", true), "Đặt lại mật khẩu thành công.");
  }

  @PostMapping("/verify-email")
  @Transactional
  public ApiResponse<Map<String, Object>> verifyEmail(@RequestBody Map<String, Object> body) {
    String token = firstString(body, "token");
    if (token == null) {
      throw new BusinessException("TOKEN_REQUIRED", "Token xác minh email là bắt buộc.");
    }
    Map<String, Object> row = validEmailVerificationToken(token);
    UUID userId = (UUID) row.get("userId");
    jdbc.update("update users set email_verified = true, updated_at = now() where id = ?", userId);
    jdbc.update("update email_verification_tokens set used_at = now() where id = ?", row.get("id"));
    db.userById(userId).ifPresent(user ->
        db.audit(userId, user.get("role").toString(), "auth.email_verified", "user", userId, "Người dùng xác minh email thành công."));
    return ApiResponse.ok(Map.of("verified", true), "Email đã được xác minh.");
  }

  private Map<String, Object> authPayload(Map<String, Object> user, HttpServletRequest request) {
    RefreshTokenService.TokenPair pair = refreshTokenService.issueTokenPair(user, ip(request), userAgent(request));
    Map<String, Object> payload = tokenPayload(pair);
    payload.put("user", user);
    return payload;
  }

  private Map<String, Object> tokenPayload(RefreshTokenService.TokenPair pair) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accessToken", pair.accessToken());
    payload.put("refreshToken", pair.refreshToken());
    return payload;
  }

  private String ip(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private String userAgent(HttpServletRequest request) {
    return request.getHeader("User-Agent");
  }

  private Map<String, Object> createPasswordResetToken(UUID userId, String email, HttpServletRequest request) {
    String token = randomToken();
    OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.auth().passwordResetTtl());
    UUID id = jdbc.queryForObject("""
        insert into password_reset_tokens(user_id, token_hash, expires_at, ip_address, user_agent)
        values (?, ?, ?, ?, ?)
        returning id
        """, UUID.class, userId, hash(token), expiresAt, ip(request), userAgent(request));
    String link = frontendUrl("/reset-password?token=" + token);
    enqueueAuthEmail(userId, email, "password_reset", "Đặt lại mật khẩu", "Mở link này để đặt lại mật khẩu: " + link, link);
    return Map.of("id", id.toString(), "token", token, "link", link, "expiresAt", expiresAt.toString());
  }

  private Map<String, Object> createEmailVerificationToken(UUID userId, String email, HttpServletRequest request) {
    String token = randomToken();
    OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.auth().emailVerificationTtl());
    UUID id = jdbc.queryForObject("""
        insert into email_verification_tokens(user_id, token_hash, expires_at, ip_address, user_agent)
        values (?, ?, ?, ?, ?)
        returning id
        """, UUID.class, userId, hash(token), expiresAt, ip(request), userAgent(request));
    String link = frontendUrl("/verify-email?token=" + token);
    enqueueAuthEmail(userId, email, "email_verification", "Xác minh email", "Mở link này để xác minh email: " + link, link);
    return Map.of("id", id.toString(), "token", token, "link", link, "expiresAt", expiresAt.toString());
  }

  private Map<String, Object> validPasswordResetToken(String token) {
    return db.optional("""
        select id, user_id, expires_at
        from password_reset_tokens
        where token_hash = ? and used_at is null and expires_at > now()
        order by created_at desc
        limit 1
        for update
        """, (rs, row) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getObject("id", UUID.class));
          m.put("userId", rs.getObject("user_id", UUID.class));
          m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
          return m;
        }, hash(token)).orElseThrow(() ->
        new BusinessException("INVALID_RESET_TOKEN", "Token đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.", HttpStatus.BAD_REQUEST));
  }

  private Map<String, Object> validEmailVerificationToken(String token) {
    return db.optional("""
        select id, user_id, expires_at
        from email_verification_tokens
        where token_hash = ? and used_at is null and expires_at > now()
        order by created_at desc
        limit 1
        for update
        """, (rs, row) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getObject("id", UUID.class));
          m.put("userId", rs.getObject("user_id", UUID.class));
          m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
          return m;
        }, hash(token)).orElseThrow(() ->
        new BusinessException("INVALID_VERIFY_TOKEN", "Token xác minh email không hợp lệ hoặc đã hết hạn.", HttpStatus.BAD_REQUEST));
  }

  private void enqueueAuthEmail(UUID userId, String email, String type, String subject, String body, String actionUrl) {
    jdbc.update("""
        insert into auth_email_outbox(user_id, email, type, subject, body, action_url)
        values (?, ?, ?, ?, ?, ?)
        """, userId, email.trim().toLowerCase(), type, subject, body, actionUrl);
  }

  private void includeDevToken(Map<String, Object> payload, String prefix, Map<String, Object> token) {
    if (!properties.auth().exposeDevTokens()) return;
    payload.put(prefix + "Token", token.get("token"));
    payload.put(prefix + "Link", token.get("link"));
    payload.put(prefix + "ExpiresAt", token.get("expiresAt"));
  }

  private String frontendUrl(String path) {
    String base = properties.auth().frontendBaseUrl();
    if (base == null || base.isBlank()) base = "http://localhost:3000";
    return base.replaceAll("/+$", "") + path;
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash auth token", ex);
    }
  }

  private String firstString(Map<String, Object> body, String... keys) {
    if (body == null) return null;
    for (String key : keys) {
      Object value = body.get(key);
      if (value != null && !value.toString().isBlank()) return value.toString();
    }
    return null;
  }

  public record RegisterRequest(
      @Email String email,
      @Size(min = 8, message = "Mật khẩu tối thiểu 8 ký tự") String password,
      @NotBlank String fullName,
      @Pattern(regexp = "^[0-9+() .-]{0,20}$", message = "Số điện thoại không hợp lệ") String phone,
      @NotBlank String role
  ) {}

  public record LoginRequest(@Email String email, @NotBlank String password) {}

  public record RefreshTokenRequest(@NotBlank String refreshToken) {}
}
