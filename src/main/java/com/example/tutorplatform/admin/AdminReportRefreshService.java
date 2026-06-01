package com.example.tutorplatform.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AdminReportRefreshService {
  private static final Logger log = LoggerFactory.getLogger(AdminReportRefreshService.class);
  private static final List<String> MATERIALIZED_VIEWS = List.of(
      "admin_report_overview_mv",
      "admin_report_request_trends_mv",
      "admin_report_conversion_funnel_mv",
      "admin_report_subject_distribution_mv",
      "admin_report_tutor_status_distribution_mv",
      "admin_report_teaching_mode_distribution_mv",
      "admin_report_revenue_mv",
      "admin_report_payment_status_distribution_mv"
  );

  private final JdbcTemplate jdbc;
  private final AdminReportService adminReportService;
  private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
  private volatile OffsetDateTime lastRefreshAt;

  public AdminReportRefreshService(JdbcTemplate jdbc, AdminReportService adminReportService) {
    this.jdbc = jdbc;
    this.adminReportService = adminReportService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void refreshAfterStartup() {
    CompletableFuture.runAsync(this::refreshAll);
  }

  @Scheduled(
      fixedDelayString = "${app.report.refresh-delay-ms:300000}",
      initialDelayString = "${app.report.initial-refresh-delay-ms:60000}"
  )
  public void refreshAll() {
    if (!refreshRunning.compareAndSet(false, true)) return;
    try {
      boolean refreshedAny = false;
      for (String view : MATERIALIZED_VIEWS) {
        try {
          refresh(view);
          refreshedAny = true;
        } catch (DataAccessException ex) {
          log.warn("Admin report materialized view refresh failed for {}", view, ex);
        }
      }
      if (refreshedAny) {
        lastRefreshAt = OffsetDateTime.now();
        adminReportService.clearCache();
      }
    } finally {
      refreshRunning.set(false);
    }
  }

  public boolean isRefreshRunning() {
    return refreshRunning.get();
  }

  public OffsetDateTime lastRefreshAt() {
    return lastRefreshAt;
  }

  private void refresh(String viewName) {
    try {
      jdbc.execute("refresh materialized view concurrently " + viewName);
    } catch (DataAccessException concurrentRefreshFailed) {
      log.debug("Concurrent admin report refresh failed for {}; retrying non-concurrently", viewName, concurrentRefreshFailed);
      jdbc.execute("refresh materialized view " + viewName);
    }
  }
}
