package br.com.fiap.fiapx.processing.infrastructure.persistence.repository;

import br.com.fiap.fiapx.processing.infrastructure.persistence.entity.ProcessingJobEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.*;

public interface SpringProcessingJobRepository extends JpaRepository<ProcessingJobEntity, UUID> {
    @Query(value = """
        SELECT i.result_json FROM processing_result_intents i JOIN processing_jobs j ON j.id=i.job_id
        WHERE j.result_json IS NULL OR
            CAST(j.result_json AS jsonb)->>'objectKey' <> CAST(i.result_json AS jsonb)->>'objectKey'
        ORDER BY i.cleanup_checked_at NULLS FIRST, i.created_at, i.attempt_id LIMIT :limit
        """, nativeQuery = true)
    List<String> abandonedResults(int limit);

    @Query(value = "SELECT count(*) FROM processing_attempts WHERE job_id=:job AND attempt_id=:producer", nativeQuery = true)
    int attemptBelongs(UUID job, UUID producer);

    @Modifying
    @Query(value = "UPDATE processing_result_intents SET cleanup_checked_at=clock_timestamp() WHERE attempt_id=:producer", nativeQuery = true)
    int checkedResult(UUID producer);

    @Modifying
    @Query(value = "INSERT INTO processing_jobs(id,owner_id,request_json) VALUES (:id,:owner,:request) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int insertNew(UUID id, UUID owner, String request);

    @Query(value = "SELECT * FROM processing_jobs WHERE id=:id FOR UPDATE", nativeQuery = true)
    Optional<ProcessingJobEntity> lock(UUID id);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Modifying
    @Query(value = "INSERT INTO processing_inbox(event_id,job_id,request_json) VALUES (:event,:job,:request) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertInbox(UUID event, UUID job, String request);

    @Query(value = "SELECT request_json FROM processing_inbox WHERE event_id=:event", nativeQuery = true)
    String inboxRequest(UUID event);

    @Modifying
    @Query(value = "INSERT INTO processing_attempts(attempt_id,job_id,attempt,acquired_at,lease_until) VALUES (:token,:job,:attempt,:now,:lease)", nativeQuery = true)
    int insertAttempt(UUID token, UUID job, int attempt, Instant now, Instant lease);

    @Modifying
    @Query(value = "UPDATE processing_attempts SET lease_until=:lease WHERE attempt_id=:token", nativeQuery = true)
    int renewAttempt(UUID token, Instant lease);

    @Modifying
    @Query(value = "UPDATE processing_attempts SET media_started_at=:now WHERE attempt_id=:token", nativeQuery = true)
    int startMedia(UUID token, Instant now);

    @Modifying
    @Query(value = "UPDATE processing_attempts SET ended_at=:now WHERE job_id=:job AND ended_at IS NULL", nativeQuery = true)
    int endAttempts(UUID job, Instant now);

    @Modifying
    @Query(value = "INSERT INTO processing_result_intents(attempt_id,job_id,result_json) VALUES (:token,:job,:result)", nativeQuery = true)
    int resultIntent(UUID token, UUID job, String result);

    @Modifying
    @Query(value = "UPDATE processing_inbox SET completed_at=:now WHERE job_id=:job AND completed_at IS NULL", nativeQuery = true)
    int finishInbox(UUID job, Instant now);

    @Modifying
    @Query(value = """
        INSERT INTO processing_outbox(event_id,job_id,event_version,event_type,payload,occurred_at,available_at)
        VALUES (:event,:job,:version,:type,:payload,:now,:now)
        """, nativeQuery = true)
    int enqueue(UUID event, UUID job, long version, String type, String payload, Instant now);

    @Query(value = "SELECT count(*) FROM processing_outbox WHERE job_id=:job AND event_version=:version AND event_type IN ('ProcessingCompleted','ProcessingFailed')", nativeQuery = true)
    int terminalEvents(UUID job, long version);

    @Modifying
    @Query(value = """
        UPDATE processing_outbox SET published_at=NULL,available_at=clock_timestamp(),claim_token=NULL,claim_until=NULL
        WHERE job_id=:job AND event_version=:version AND event_type IN ('ProcessingCompleted','ProcessingFailed')
          AND published_at IS NOT NULL
        """, nativeQuery = true)
    int rescheduleTerminal(UUID job, long version);
}
