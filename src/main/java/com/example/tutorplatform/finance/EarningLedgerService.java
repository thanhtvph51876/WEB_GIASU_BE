package com.example.tutorplatform.finance;

import com.example.tutorplatform.db.DbService;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EarningLedgerService {
  private final JdbcTemplate jdbc;

  public EarningLedgerService(DbService db) {
    this.jdbc = db.jdbc();
  }

  public void record(UUID earningId, UUID tutorId, UUID paymentId, UUID payoutId, String entryType, int amount, String description) {
    jdbc.update("""
        insert into earning_ledger(earning_id, tutor_id, payment_id, payout_id, entry_type, amount, description)
        values (?, ?, ?, ?, ?, ?, ?)
        """, earningId, tutorId, paymentId, payoutId, entryType, amount, description);
  }

  public void recordForEarning(UUID earningId, UUID payoutId, String entryType, int amount, String description) {
    Map<String, Object> earning = jdbc.queryForMap("""
        select tutor_id, payment_id
        from tutor_earnings
        where id = ?
        """, earningId);
    record(
        earningId,
        (UUID) earning.get("tutor_id"),
        (UUID) earning.get("payment_id"),
        payoutId,
        entryType,
        amount,
        description
    );
  }
}
