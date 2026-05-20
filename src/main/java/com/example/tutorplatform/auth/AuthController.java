package com.example.tutorplatform.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
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
  private final Environment environment;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthController(DbService db, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService, Environment environment) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.passwordEncoder = passwordEncoder;
    this.refreshTokenService = refreshTokenService;
    this.environment = environment;
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
    String verificationToken = createEmailVerificationToken(userId);

    Map<String, Object> user = db.userById(userId).orElseThrow();
    db.audit(userId, request.role(), "auth.register", "user", userId, "Người dùng đăng ký tài khoản mới.");
    Map<String, Object> payload = authPayload(user, servletRequest);
    if (exposeDevTokens()) payload.put("emailVerificationToken", verificationToken);
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
  public ApiResponse<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("accepted", true);
    db.userByEmail(request.email()).ifPresent(user -> {
      UUID userId = UUID.fromString(user.get("id").toString());
      String token = randomToken();
      jdbc.update("""
          insert into password_reset_tokens(user_id, token_hash, expires_at)
          values (?, ?, now() + interval '30 minutes')
          """, userId, hash(token));
      db.audit(userId, user.get("role").toString(), "auth.password_reset_requested", "user", userId, "Người dùng yêu cầu đặt lại mật khẩu.");
      if (exposeDevTokens()) data.put("resetToken", token);
    });
    return ApiResponse.ok(data, "Đã ghi nhận yêu cầu đặt lại mật khẩu.");
  }

  @PostMapping("/reset-password")
  @Transactional
  public ApiResponse<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    UUID userId = consumeToken("password_reset_tokens", request.token());
    jdbc.update("update users set password_hash = ?, updated_at = now() where id = ?", passwordEncoder.encode(request.password()), userId);
    refreshTokenService.revokeAllForUser(userId, "Người dùng đặt lại mật khẩu, thu hồi toàn bộ refresh token.");
    db.userById(userId).ifPresent(user -> db.audit(userId, user.get("role").toString(), "auth.password_reset_completed", "user", userId, "Người dùng đặt lại mật khẩu thành công."));
    return ApiResponse.ok(Map.of("accepted", true), "Mật khẩu đã được cập nhật.");
  }

  @PostMapping("/verify-email")
  @Transactional
  public ApiResponse<Map<String, Object>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
    UUID userId = consumeToken("email_verification_tokens", request.token());
    jdbc.update("update users set email_verified = true, updated_at = now() where id = ?", userId);
    db.userById(userId).ifPresent(user -> db.audit(userId, user.get("role").toString(), "auth.email_verified", "user", userId, "Người dùng xác minh email thành công."));
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

  private String createEmailVerificationToken(UUID userId) {
    String token = randomToken();
    jdbc.update("""
        insert into email_verification_tokens(user_id, token_hash, expires_at)
        values (?, ?, now() + interval '24 hours')
        """, userId, hash(token));
    return token;
  }

  private boolean exposeDevTokens() {
    return Arrays.stream(environment.getActiveProfiles()).noneMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
  }

  private UUID consumeToken(String table, String rawToken) {
    String tokenHash = hash(rawToken);
    UUID userId = db.optional("""
        select user_id from """ + table + """
        where token_hash = ? and used = false and expires_at > now()
        order by created_at desc
        limit 1
        for update
        """, (rs, row) -> rs.getObject("user_id", UUID.class), tokenHash)
        .orElseThrow(() -> new BusinessException("INVALID_TOKEN", "Token không hợp lệ hoặc đã hết hạn.", HttpStatus.UNAUTHORIZED));
    jdbc.update("update " + table + " set used = true, used_at = now() where token_hash = ?", tokenHash);
    return userId;
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash token", ex);
    }
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

  public record ForgotPasswordRequest(@NotBlank @Email String email) {}

  public record ResetPasswordRequest(@NotBlank String token, @JsonAlias("newPassword") @Size(min = 8, message = "Mật khẩu tối thiểu 8 ký tự") String password) {}

  public record VerifyEmailRequest(@NotBlank String token) {}
}
