package br.com.fiap.fiapx.processing.infrastructure.persistence.adapter;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Short claims with fencing; no SQL transaction spans the SQS send. */
public final class ProcessingOutbox {
    public record Publication(UUID id,UUID token,String body) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ProcessingOutbox(JdbcTemplate jdbc,TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public Optional<Publication> claim() {
        return tx.execute(status -> jdbc.query("""
            WITH next AS (
                SELECT event_id FROM processing_outbox
                WHERE published_at IS NULL AND available_at<=clock_timestamp()
                  AND (claim_until IS NULL OR claim_until<=clock_timestamp())
                ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE processing_outbox o SET claim_token=?,claim_until=clock_timestamp()+interval '120 seconds'
            FROM next WHERE o.event_id=next.event_id RETURNING o.event_id,o.claim_token,o.payload
            """,(rs,row)->new Publication(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),
                UUID.randomUUID()).stream().findFirst());
    }
    public void published(Publication event) {
        tx.executeWithoutResult(status -> jdbc.update("""
            UPDATE processing_outbox SET published_at=clock_timestamp(),claim_token=NULL,claim_until=NULL
            WHERE event_id=? AND claim_token=? AND claim_until>clock_timestamp() AND published_at IS NULL
            """,event.id(),event.token()));
    }
    public void retry(Publication event) {
        tx.executeWithoutResult(status -> jdbc.update("""
            UPDATE processing_outbox SET available_at=clock_timestamp()+interval '30 seconds',claim_token=NULL,claim_until=NULL
            WHERE event_id=? AND claim_token=? AND claim_until>clock_timestamp() AND published_at IS NULL
            """,event.id(),event.token()));
    }
}
