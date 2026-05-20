package com.example.tutorplatform.security;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.db.DbService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtService jwtService;
  private final DbService db;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public JwtAuthenticationFilter(JwtService jwtService, DbService db) {
    this.jwtService = jwtService;
    this.db = db;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        Claims claims = jwtService.parse(header.substring(7));
        if (!"access".equals(claims.get("type"))) {
          reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "Access token không hợp lệ.");
          return;
        }
        UUID userId = UUID.fromString(claims.getSubject());
        Map<String, Object> user = db.userById(userId).orElse(null);
        if (user == null) {
          reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "Phiên đăng nhập không hợp lệ.");
          return;
        }
        if (!"active".equals(user.get("status"))) {
          reject(response, HttpServletResponse.SC_FORBIDDEN, "USER_NOT_ACTIVE", "Tài khoản không còn hoạt động.");
          return;
        }
        String role = String.valueOf(claims.get("role")).toUpperCase();
        var auth = new UsernamePasswordAuthenticationToken(
            claims.getSubject(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (Exception ignored) {
        SecurityContextHolder.clearContext();
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "Token không hợp lệ hoặc đã hết hạn.");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
    SecurityContextHolder.clearContext();
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message, null));
  }
}
