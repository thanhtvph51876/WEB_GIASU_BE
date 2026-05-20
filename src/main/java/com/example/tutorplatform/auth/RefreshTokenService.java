package com.example.tutorplatform.auth;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.security.JwtService;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final JwtService jwtService;

  public RefreshTokenService(DbService db, JwtService jwtService) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.jwtService = jwtService;
  }

  @Transactional
  public TokenPair issueTokenPair(Map<String, Object> user, String ipAddress, String userAgent) {
    UUID userId = UUID.fromString(user.get("id").toString());
    String role = user.get("role").toString();
    return new TokenPair(
        jwtService.accessToken(userId.toString(), role),
        issueRefreshToken(userId, role, ipAddress, userAgent)
    );
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public TokenPair rotate(String rawRefreshToken, String ipAddress, String userAgent) {
    Claims claims = parseRefreshClaims(rawRefreshToken);
    UUID userId = UUID.fromString(claims.getSubject());
    String tokenHash = hash(rawRefreshToken);

    Map<String, Object> tokenRow = db.optional("""
        select * from refresh_tokens
        where token_hash = ?
        for update
        """, (rs, row) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getObject("id", UUID.class));
          m.put("userId", rs.getObject("user_id", UUID.class));
          m.put("revoked", rs.getBoolean("revoked"));
          m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
          return m;
        }, tokenHash).orElseThrow(() -> invalidRefreshToken());

    if (!userId.equals(tokenRow.get("userId"))) {
      throw invalidRefreshToken();
    }
    if (Boolean.TRUE.equals(tokenRow.get("revoked"))) {
      throw new BusinessException("REFRESH_TOKEN_REVOKED", "Refresh token đã bị thu hồi.", HttpStatus.UNAUTHORIZED);
    }
    OffsetDateTime expiresAt = (OffsetDateTime) tokenRow.get("expiresAt");
    if (expiresAt == null || expiresAt.isBefore(OffsetDateTime.now())) {
      revokeByHash(tokenHash);
      throw new BusinessException("REFRESH_TOKEN_EXPIRED", "Refresh token đã hết hạn.", HttpStatus.UNAUTHORIZED);
    }

    Map<String, Object> user = activeUser(userId);
    String role = user.get("role").toString();
    String newRefreshToken = jwtService.refreshToken(userId.toString(), role);
    UUID newTokenId = store(userId, newRefreshToken, ipAddress, userAgent);
    jdbc.update("""
        update refresh_tokens
        set revoked = true, revoked_at = now(), replaced_by_token_id = ?
        where id = ?
        """, newTokenId, tokenRow.get("id"));
    db.audit(userId, role, "auth.refresh_token_rotate", "refreshToken", (UUID) tokenRow.get("id"), "Refresh token được xoay vòng an toàn.");
    return new TokenPair(jwtService.accessToken(userId.toString(), role), newRefreshToken);
  }

  @Transactional
  public void revoke(String rawRefreshToken) {
    Claims claims = parseRefreshClaims(rawRefreshToken);
    UUID userId = UUID.fromString(claims.getSubject());
    int updated = jdbc.update("""
        update refresh_tokens
        set revoked = true, revoked_at = now()
        where token_hash = ? and revoked = false
        """, hash(rawRefreshToken));
    if (updated > 0) {
      db.audit(userId, String.valueOf(claims.get("role")), "auth.refresh_token_revoke", "user", userId, "Refresh token đã được thu hồi.");
    }
  }

  @Transactional
  public void revokeAllForUser(UUID userId, String reason) {
    jdbc.update("""
        update refresh_tokens
        set revoked = true, revoked_at = now()
        where user_id = ? and revoked = false
        """, userId);
    db.userById(userId).ifPresent(user ->
        db.audit(userId, user.get("role").toString(), "auth.refresh_token_revoke_all", "user", userId, reason));
  }

  private String issueRefreshToken(UUID userId, String role, String ipAddress, String userAgent) {
    String raw = jwtService.refreshToken(userId.toString(), role);
    store(userId, raw, ipAddress, userAgent);
    return raw;
  }

  private UUID store(UUID userId, String rawToken, String ipAddress, String userAgent) {
    Claims claims = parseRefreshClaims(rawToken);
    return jdbc.queryForObject("""
        insert into refresh_tokens(user_id, token_hash, expires_at, created_at, ip_address, user_agent)
        values (?, ?, ?, now(), ?, ?)
        returning id
        """, UUID.class, userId, hash(rawToken), claims.getExpiration().toInstant().atOffset(java.time.ZoneOffset.UTC), ipAddress, userAgent);
  }

  private Map<String, Object> activeUser(UUID userId) {
    Map<String, Object> user = db.userById(userId)
        .orElseThrow(() -> invalidRefreshToken());
    String status = user.get("status").toString();
    if (!"active".equals(status)) {
      revokeAllForUser(userId, "Thu hồi toàn bộ refresh token vì tài khoản không còn active.");
      throw new BusinessException("USER_NOT_ACTIVE", "Tài khoản không còn hoạt động.", HttpStatus.FORBIDDEN);
    }
    return user;
  }

  private Claims parseRefreshClaims(String rawRefreshToken) {
    try {
      Claims claims = jwtService.parse(rawRefreshToken);
      if (!"refresh".equals(claims.get("type"))) {
        throw invalidRefreshToken();
      }
      return claims;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw invalidRefreshToken();
    }
  }

  private void revokeByHash(String tokenHash) {
    jdbc.update("""
        update refresh_tokens
        set revoked = true, revoked_at = now()
        where token_hash = ? and revoked = false
        """, tokenHash);
  }

  private BusinessException invalidRefreshToken() {
    return new BusinessException("INVALID_REFRESH_TOKEN", "Refresh token không hợp lệ.", HttpStatus.UNAUTHORIZED);
  }

  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash refresh token", ex);
    }
  }

  public record TokenPair(String accessToken, String refreshToken) {}
}
