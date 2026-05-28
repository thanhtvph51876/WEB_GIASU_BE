package com.example.tutorplatform.tutor;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.db.DbService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tutor")
public class TutorPerformanceController {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public TutorPerformanceController(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  @GetMapping("/performance")
  public ApiResponse<Map<String, Object>> performance() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    Map<String, Object> raw = jdbc.queryForMap("""
        select
          coalesce(avg(r.rating), 0) average_rating,
          count(distinct cs.id) filter (where cs.status = 'completed') total_completed_sessions,
          coalesce(sum(te.net_amount) filter (where te.status in ('available','paid','payout_pending')),0) monthly_earnings,
          coalesce(sum(te.net_amount) filter (where te.status = 'payout_pending'),0) payout_pending,
          count(distinct tp.id) filter (where tp.status = 'ACCEPTED') accepted_proposals,
          count(distinct tp.id) total_proposals,
          count(distinct tb.id) filter (where tb.status in ('converted','converted_to_class')) converted_trials,
          count(distinct tb.id) filter (where tb.status in ('completed','converted','converted_to_class','rejected_after_trial')) completed_trials,
          count(distinct tb.id) filter (where tb.status in ('cancelled_by_tutor','cancelled')) cancelled_bookings,
          count(distinct tb.id) filter (where tb.status in ('no_show_tutor')) no_show_bookings
        from tutor_profiles tutor
        left join class_sessions cs on cs.tutor_id = tutor.id and cs.scheduled_start >= date_trunc('month', now())
        left join tutor_earnings te on te.tutor_id = tutor.id and te.created_at >= date_trunc('month', now())
        left join tutor_proposals tp on tp.tutor_id = tutor.id and tp.created_at >= date_trunc('month', now())
        left join trial_bookings tb on tb.tutor_id = tutor.id and tb.created_at >= date_trunc('month', now())
        left join reviews r on r.tutor_id = tutor.id and r.status = 'visible'
        where tutor.id = ?
        group by tutor.id
        """, tutorId);
    double totalProposals = number(raw.get("total_proposals"));
    double accepted = number(raw.get("accepted_proposals"));
    double completedTrials = number(raw.get("completed_trials"));
    double converted = number(raw.get("converted_trials"));
    double cancelled = number(raw.get("cancelled_bookings"));
    double noShow = number(raw.get("no_show_bookings"));
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("responseRate", jdbc.queryForObject("select response_rate from tutor_profiles where id = ?", Double.class, tutorId));
    metrics.put("averageResponseTime", 0);
    metrics.put("proposalAcceptanceRate", totalProposals == 0 ? 0 : Math.round(accepted * 10000 / totalProposals) / 100.0);
    metrics.put("trialToClassConversionRate", completedTrials == 0 ? 0 : Math.round(converted * 10000 / completedTrials) / 100.0);
    metrics.put("cancellationRate", completedTrials + cancelled == 0 ? 0 : Math.round(cancelled * 10000 / (completedTrials + cancelled)) / 100.0);
    metrics.put("noShowRate", completedTrials + noShow == 0 ? 0 : Math.round(noShow * 10000 / (completedTrials + noShow)) / 100.0);
    metrics.put("averageRating", number(raw.get("average_rating")));
    metrics.put("totalCompletedSessions", (int) number(raw.get("total_completed_sessions")));
    metrics.put("monthlyEarnings", (int) number(raw.get("monthly_earnings")));
    metrics.put("payoutPending", (int) number(raw.get("payout_pending")));
    metrics.put("repeatParentCount", 0);
    return ApiResponse.ok(metrics);
  }

  private double number(Object value) {
    if (value instanceof Number number) return number.doubleValue();
    return value == null ? 0 : Double.parseDouble(value.toString());
  }
}
