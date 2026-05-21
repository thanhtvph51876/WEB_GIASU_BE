package com.example.tutorplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityHardeningIntegrationTest {
  private static final Path UPLOAD_DIR = createUploadDir();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("tutor_platform_test")
      .withUsername("postgres")
      .withPassword("postgres");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.jwt.secret", () -> "test-secret-with-at-least-32-bytes-for-jwt");
    registry.add("app.jwt.access-token-expire-minutes", () -> 15);
    registry.add("app.jwt.refresh-token-expire-days", () -> 7);
    registry.add("app.upload.dir", () -> UPLOAD_DIR.toString());
    registry.add("app.auth.frontend-base-url", () -> "http://localhost:3000");
    registry.add("app.auth.password-reset-token-expire-minutes", () -> 30);
    registry.add("app.auth.email-verification-token-expire-minutes", () -> 1440);
    registry.add("app.auth.expose-dev-tokens", () -> false);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void resetData() {
    jdbc.execute("truncate table users, subjects, grade_levels cascade");
    ensurePaymentSettings();
  }

  @Test
  void accessTokenWorksRefreshTokenCannotCallProtectedApiAndLogoutRevokesRefreshToken() throws Exception {
    user("student@example.com", "Password123!", "student", "active");

    Tokens tokens = login("student@example.com", "Password123!");

    mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokens.accessToken())))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokens.refreshToken())))
        .andExpect(status().isUnauthorized());

    mvc.perform(post("/api/v1/auth/logout")
            .header("Authorization", bearer(tokens.accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("refreshToken", tokens.refreshToken()))))
        .andExpect(status().isOk());

    mvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("refreshToken", tokens.refreshToken()))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void suspendedUserCannotRefresh() throws Exception {
    UUID student = user("student@example.com", "Password123!", "student", "active");
    Tokens tokens = login("student@example.com", "Password123!");

    jdbc.update("update users set status = 'suspended' where id = ?", student);

    mvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("refreshToken", tokens.refreshToken()))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminRoutesRequireAdminRole() throws Exception {
    user("student@example.com", "Password123!", "student", "active");
    user("admin@example.com", "Password123!", "admin", "active");

    mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(login("student@example.com", "Password123!").accessToken())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(login("admin@example.com", "Password123!").accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void userCannotReadOrCancelOtherUsersLearningRequest() throws Exception {
    UUID owner = user("owner@example.com", "Password123!", "student", "active");
    user("other@example.com", "Password123!", "student", "active");
    UUID subject = subject();
    UUID request = jdbc.queryForObject("""
        insert into learning_requests(request_code, requester_id, student_name, subject_id, learning_mode, status)
        values ('REQ-TEST-1', ?, 'Student A', ?, 'online', 'new')
        returning id
        """, UUID.class, owner, subject);
    String otherAccess = login("other@example.com", "Password123!").accessToken();

    mvc.perform(get("/api/v1/learning-requests/" + request).header("Authorization", bearer(otherAccess)))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/learning-requests/" + request + "/cancel").header("Authorization", bearer(otherAccess)))
        .andExpect(status().isForbidden());
  }

  @Test
  void tutorCannotAcceptOtherTutorsBookingAndSuspendedTutorCannotAccept() throws Exception {
    UUID student = user("student@example.com", "Password123!", "student", "active");
    UUID tutorUserA = user("tutor-a@example.com", "Password123!", "tutor", "active");
    UUID tutorUserB = user("tutor-b@example.com", "Password123!", "tutor", "active");
    UUID tutorA = tutor(tutorUserA, "approved");
    UUID tutorB = tutor(tutorUserB, "approved");
    UUID subject = subject();
    UUID booking = booking(student, tutorB, subject, "pending");

    mvc.perform(post("/api/v1/tutor/bookings/" + booking + "/accept")
            .header("Authorization", bearer(login("tutor-a@example.com", "Password123!").accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    jdbc.update("update tutor_profiles set status = 'suspended' where id = ?", tutorB);
    mvc.perform(post("/api/v1/tutor/bookings/" + booking + "/accept")
            .header("Authorization", bearer(login("tutor-b@example.com", "Password123!").accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void privateFileRequiresOwnerOrAdmin() throws Exception {
    UUID owner = user("owner@example.com", "Password123!", "student", "active");
    user("other@example.com", "Password123!", "student", "active");
    user("admin@example.com", "Password123!", "admin", "active");
    Files.createDirectories(UPLOAD_DIR.resolve("private"));
    Files.writeString(UPLOAD_DIR.resolve("private/test.txt"), "private-file");
    UUID fileId = jdbc.queryForObject("""
        insert into uploaded_files(owner_id, file_name, original_file_name, file_url, file_size, mime_type, storage_path, visibility)
        values (?, 'test.txt', 'test.txt', '/api/v1/files/test', 12, 'text/plain', 'private/test.txt', 'private')
        returning id
        """, UUID.class, owner);

    mvc.perform(get("/api/v1/files/" + fileId)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", bearer(login("other@example.com", "Password123!").accessToken())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", bearer(login("owner@example.com", "Password123!").accessToken())))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", bearer(login("admin@example.com", "Password123!").accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void payoutLocksOnlyAllocatedEarningsAndApproveMarksOnlyThoseItems() throws Exception {
    UUID admin = user("admin@example.com", "Password123!", "admin", "active");
    UUID tutorUser = user("tutor@example.com", "Password123!", "tutor", "active");
    UUID tutor = tutor(tutorUser, "approved");
    approvedVerification(tutorUser, "tutor_identity");
    earning(tutor, 100_000);
    earning(tutor, 200_000);

    String tutorAccess = login("tutor@example.com", "Password123!").accessToken();
    String response = mvc.perform(post("/api/v1/tutor/payouts")
            .header("Authorization", bearer(tutorAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("amount", 150_000, "bankName", "VCB", "bankAccount", "123", "accountHolder", "Tutor"))))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    UUID payoutId = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());

    Integer locked = jdbc.queryForObject("select coalesce(sum(net_amount),0) from tutor_earnings where tutor_id = ? and status = 'payout_pending'", Integer.class, tutor);
    Integer itemTotal = jdbc.queryForObject("select coalesce(sum(amount),0) from payout_earning_items where payout_id = ?", Integer.class, payoutId);
    assertThat(locked).isEqualTo(150_000);
    assertThat(itemTotal).isEqualTo(150_000);

    mvc.perform(post("/api/v1/admin/payouts/" + payoutId + "/approve")
            .header("Authorization", bearer(login("admin@example.com", "Password123!").accessToken())))
        .andExpect(status().isOk());

    Integer paid = jdbc.queryForObject("select coalesce(sum(net_amount),0) from tutor_earnings where tutor_id = ? and status = 'paid'", Integer.class, tutor);
    Integer available = jdbc.queryForObject("select coalesce(sum(net_amount),0) from tutor_earnings where tutor_id = ? and status = 'available'", Integer.class, tutor);
    assertThat(paid).isEqualTo(150_000);
    assertThat(available).isEqualTo(150_000);
    assertThat(admin).isNotNull();
  }

  @Test
  void registerCannotEscalateToAdminRole() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of(
                "email", "hacker@example.com",
                "password", "Password123!",
                "fullName", "Hacker",
                "phone", "0900000000",
                "role", "admin"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void publicLearningRequestDoesNotReturnPii() throws Exception {
    UUID student = user("student-pii@example.com", "Password123!", "student", "active");
    UUID subject = subject();
    jdbc.update("""
        insert into learning_requests(request_code, requester_id, student_name, parent_name, phone, email,
          subject_id, learning_mode, province, district, preferred_schedule, note, status, public_visible)
        values ('REQ-PII-1', ?, 'Hidden Student', 'Hidden Parent', '0900000000', 'private@example.com',
          ?, 'online', 'TP.HCM', 'Quận 1', 'Tối thứ 2', 'Ghi chú riêng', 'new', true)
        """, student, subject);

    String response = mvc.perform(get("/api/v1/public/learning-requests"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    JsonNode first = objectMapper.readTree(response).path("data").get(0);
    assertThat(first.has("studentName")).isFalse();
    assertThat(first.has("phone")).isFalse();
    assertThat(first.has("email")).isFalse();
    assertThat(first.has("note")).isFalse();
  }

  @Test
  void studentUploadAgreementSubmitAndAdminApproveVerification() throws Exception {
    user("student-verify@example.com", "Password123!", "student", "active");
    user("admin-verify@example.com", "Password123!", "admin", "active");
    String studentAccess = login("student-verify@example.com", "Password123!").accessToken();
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "student-card.pdf",
        "application/pdf",
        "%PDF-1.4\n%%EOF".getBytes()
    );

    String uploadResponse = mvc.perform(multipart("/api/v1/student/verifications/student-card/upload")
            .file(file)
            .param("schoolName", "Demo University")
            .param("studentCode", "SV001")
            .param("fullNameInput", "Student Verify")
            .header("Authorization", bearer(studentAccess)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    UUID verificationId = UUID.fromString(objectMapper.readTree(uploadResponse).path("data").path("id").asText());
    assertThat(objectMapper.readTree(uploadResponse).path("data").path("status").asText()).isEqualTo("draft");

    mvc.perform(post("/api/v1/student/verifications/" + verificationId + "/agreement/sign")
            .header("Authorization", bearer(studentAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("signerFullName", "Student Verify", "signerEmail", "student-verify@example.com"))))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/student/verifications/" + verificationId + "/submit")
            .header("Authorization", bearer(studentAccess)))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select status from user_verifications where id = ?", String.class, verificationId)).isEqualTo("pending_review");

    mvc.perform(post("/api/v1/admin/verifications/" + verificationId + "/approve")
            .header("Authorization", bearer(login("admin-verify@example.com", "Password123!").accessToken())))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select status from user_verifications where id = ?", String.class, verificationId)).isEqualTo("approved");
    assertThat(jdbc.queryForObject("select count(*) from verification_agreements where verification_id = ?", Integer.class, verificationId)).isEqualTo(1);
  }

  @Test
  void completingSessionTwiceCreatesSinglePaymentAndEarning() throws Exception {
    UUID student = user("student-session@example.com", "Password123!", "student", "active");
    UUID tutorUser = user("tutor-session@example.com", "Password123!", "tutor", "active");
    UUID tutor = tutor(tutorUser, "approved");
    UUID subject = subject();
    UUID classId = jdbc.queryForObject("""
        insert into tutoring_classes(student_id, tutor_id, subject_id, title, learning_mode, hourly_rate, sessions_per_week, start_date, status)
        values (?, ?, ?, 'Math class', 'online', 120000, 2, current_date, 'active')
        returning id
        """, UUID.class, student, tutor, subject);
    UUID sessionId = jdbc.queryForObject("""
        insert into class_sessions(class_id, student_id, tutor_id, scheduled_start, scheduled_end, status)
        values (?, ?, ?, now(), now() + interval '1 hour', 'scheduled')
        returning id
        """, UUID.class, classId, student, tutor);
    String tutorAccess = login("tutor-session@example.com", "Password123!").accessToken();

    mvc.perform(post("/api/v1/tutor/sessions/" + sessionId + "/complete")
            .header("Authorization", bearer(tutorAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/tutor/sessions/" + sessionId + "/complete")
            .header("Authorization", bearer(tutorAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("select count(*) from payments where session_id = ?", Integer.class, sessionId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from tutor_earnings where session_id = ?", Integer.class, sessionId)).isEqualTo(1);
  }

  @Test
  void paymentGatewaySelectionIsModeSafe() throws Exception {
    UUID student = user("student@example.com", "Password123!", "student", "active");
    UUID payment = jdbc.queryForObject("""
        insert into payments(user_id, amount, description, status)
        values (?, 120000, 'Test payment', 'pending')
        returning id
        """, UUID.class, student);
    String access = login("student@example.com", "Password123!").accessToken();

    setting("paymentMode", "\"production\"");
    mvc.perform(post("/api/v1/payments/" + payment + "/create-checkout")
            .header("Authorization", bearer(access))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("gateway", "vnpay"))))
        .andExpect(status().isForbidden());

    setting("paymentMode", "\"sandbox\"");
    mvc.perform(post("/api/v1/payments/" + payment + "/create-checkout")
            .header("Authorization", bearer(access))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("gateway", "does-not-exist"))))
        .andExpect(status().isBadRequest());

    setting("enabledGateways", "[\"bank_qr\"]");
    mvc.perform(post("/api/v1/payments/" + payment + "/create-checkout")
            .header("Authorization", bearer(access))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("gateway", "vnpay"))))
        .andExpect(status().isBadRequest());
  }

  private Tokens login(String email, String password) throws Exception {
    String response = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("email", email, "password", password))))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode data = objectMapper.readTree(response).path("data");
    return new Tokens(data.path("accessToken").asText(), data.path("refreshToken").asText());
  }

  private UUID user(String email, String password, String role, String status) {
    return jdbc.queryForObject("""
        insert into users(email, password_hash, full_name, role, status, email_verified)
        values (?, ?, ?, ?, ?, true)
        returning id
        """, UUID.class, email, passwordEncoder.encode(password), email, role, status);
  }

  private UUID tutor(UUID userId, String status) {
    return jdbc.queryForObject("""
        insert into tutor_profiles(user_id, headline, bio, gender, university, major, status)
        values (?, '', '', 'other', '', '', ?)
        returning id
        """, UUID.class, userId, status);
  }

  private UUID subject() {
    return jdbc.queryForObject("""
        insert into subjects(name, slug, description)
        values ('Math', 'math-' || gen_random_uuid(), '')
        returning id
        """, UUID.class);
  }

  private UUID booking(UUID studentId, UUID tutorId, UUID subjectId, String status) {
    return jdbc.queryForObject("""
        insert into trial_bookings(student_id, tutor_id, subject_id, student_name, learning_mode, status)
        values (?, ?, ?, 'Student', 'online', ?)
        returning id
        """, UUID.class, studentId, tutorId, subjectId, status);
  }

  private void earning(UUID tutorId, int netAmount) {
    jdbc.update("""
        insert into tutor_earnings(tutor_id, gross_amount, platform_fee, net_amount, status)
        values (?, ?, 0, ?, 'available')
        """, tutorId, netAmount, netAmount);
  }

  private void approvedVerification(UUID userId, String type) {
    jdbc.update("""
        insert into user_verifications(user_id, verification_type, status)
        values (?, ?, 'approved')
        """, userId, type);
  }

  private void ensurePaymentSettings() {
    setting("paymentMode", "\"sandbox\"");
    setting("enabledGateways", "[\"bank_qr\",\"vnpay\"]");
    setting("defaultGateway", "\"bank_qr\"");
    setting("paymentTimeoutMinutes", "30");
    setting("commissionRate", "0.15");
  }

  private void setting(String key, String valueJson) {
    jdbc.update("""
        insert into system_settings(key, value, description)
        values (?, ?::jsonb, 'test')
        on conflict(key) do update set value = excluded.value, updated_at = now()
        """, key, valueJson);
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  private static Path createUploadDir() {
    try {
      return Files.createTempDirectory("tutor-platform-upload-test");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private record Tokens(String accessToken, String refreshToken) {}
}
