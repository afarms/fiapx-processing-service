package br.com.fiap.fiapx.processing.infrastructure.integration;

import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.core.gateway.ArtifactCleanupGateway;
import br.com.fiap.fiapx.processing.core.gateway.ObjectStorageGateway;
import br.com.fiap.fiapx.processing.core.gateway.MediaGateway;
import br.com.fiap.fiapx.processing.core.usecase.StoreProcessingResult;
import br.com.fiap.fiapx.processing.core.usecase.ProcessWork;
import br.com.fiap.fiapx.processing.infrastructure.messaging.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import br.com.fiap.fiapx.processing.infrastructure.storage.LocalProcessingArtifacts;
import br.com.fiap.fiapx.processing.infrastructure.storage.StorageFixtures;
import java.nio.file.*;
import java.io.IOException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import br.com.fiap.fiapx.processing.infrastructure.ProcessingApplication;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL; no broker, storage or media engine is invoked. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessingPostgresIT {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    final String schema="it_processing_"+UUID.randomUUID().toString().replace("-","");
    ConfigurableApplicationContext context;
    JdbcTemplate jdbc;
    JobGateway gateway;
    boolean created;

    @BeforeAll void start() throws Exception {
        String url=System.getenv("PROCESSING_TEST_DB_URL");
        assertNotNull(url,"Run make integration against local PostgreSQL");
        assertTrue(url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/[^?]+"),"Local database required");
        try(var c=connection(); var sql=c.createStatement()) { sql.execute("CREATE SCHEMA "+schema); created=true; }
        startApplication();
    }

    java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("PROCESSING_TEST_DB_URL"),
                System.getenv("PROCESSING_TEST_DB_USERNAME"),System.getenv("PROCESSING_TEST_DB_PASSWORD"));
    }

    void startApplication() {
        context=new SpringApplicationBuilder(ProcessingApplication.class).web(WebApplicationType.NONE).run(
                "--messaging.enabled=false", "--storage.enabled=false",
                "--spring.datasource.url="+System.getenv("PROCESSING_TEST_DB_URL"),
                "--spring.datasource.username="+System.getenv("PROCESSING_TEST_DB_USERNAME"),
                "--spring.datasource.password="+System.getenv("PROCESSING_TEST_DB_PASSWORD"),
                "--spring.datasource.hikari.schema="+schema,
                "--spring.liquibase.default-schema="+schema,
                "--spring.jpa.properties.hibernate.default_schema="+schema);
        jdbc=context.getBean(JdbcTemplate.class); gateway=context.getBean(JobGateway.class);
    }

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE processing_outbox,processing_result_intents,processing_attempts,processing_inbox,processing_jobs CASCADE");
    }

    @AfterAll void stop() throws Exception {
        if(context!=null) context.close();
        assertTrue(schema.matches("it_processing_[0-9a-f]{32}"));
        if(created) try(var c=connection(); var sql=c.createStatement()) { sql.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }

    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    void expire(UUID id) { jdbc.update("UPDATE processing_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    JobGateway.Claim prepare(ProcessingRequest request) {
        var claim=gateway.acquire(request); gateway.beginMedia(request.videoId(),claim.job().token());
        gateway.prepareResult(request.videoId(),claim.job().token(),artifact(claim.job())); return claim;
    }

    @Test void freshMigrationAndRestartPreserveJobAndOutbox() {
        var request=request(); var claim=gateway.acquire(request);
        assertEquals(2,count("databasechangelog"));
        String checksum=jdbc.queryForObject("SELECT md5sum FROM databasechangelog WHERE id='001-processing-jobs'",String.class);
        context.close(); startApplication();
        var replay=gateway.acquire(request);
        assertEquals(JobGateway.Disposition.BUSY,replay.disposition());
        assertEquals(claim.job().token(),replay.job().token());
        assertEquals(checksum,jdbc.queryForObject("SELECT md5sum FROM databasechangelog WHERE id='001-processing-jobs'",String.class));
        assertEquals(1,count("processing_jobs")); assertEquals(1,count("processing_outbox"));
    }

    WorkConsumer consumer(ProcessingRequest request,SqsClient sqs,MediaGateway media,StoreProcessingResult storage,ScheduledExecutorService scheduler) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().messageId("local-delivery").receiptHandle("receipt-current")
                        .body(WorkMessageDecoderTest.envelope(request)).build()).build());
        return new WorkConsumer(sqs,"https://sqs.us-east-1.amazonaws.com/123456789012/work",
                new WorkMessageDecoder(context.getBean(JsonMapper.class),request.bucket()),
                new ProcessWork(gateway,media,storage,limits()),gateway,limits(),scheduler,()->true);
    }

    @Test void failedCommitAckFailureAndTerminalReplayUseRealDurableState() throws Exception {
        var request=request(); var sqs=mock(SqsClient.class); var media=mock(MediaGateway.class);
        var storage=mock(StoreProcessingResult.class);
        when(storage.download(any(),any())).thenReturn(temporary.resolve("verified"));
        when(media.extract(any(),any())).thenThrow(new MediaFailure(FailureCode.INVALID_MEDIA));
        // Force failure inside the same transaction that would insert the terminal outbox.
        jdbc.execute("ALTER TABLE processing_outbox ADD CONSTRAINT reject_failed_test CHECK(event_type <> 'ProcessingFailed')");
        try (var scheduler=Executors.newSingleThreadScheduledExecutor(); var consumer=consumer(request,sqs,media,storage,scheduler)) {
            consumer.poll(); verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals("RETRY_WAIT",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingFailed'",Integer.class));
            jdbc.execute("ALTER TABLE processing_outbox DROP CONSTRAINT reject_failed_test");
            jdbc.update("UPDATE processing_jobs SET retry_at=clock_timestamp()-interval '1 second' WHERE id=?",request.videoId());
            when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(call->{
                assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingFailed'",Integer.class));
                throw new IllegalStateException("crash after commit / uncertain ACK");
            });
            consumer.poll(); consumer.poll();
            verify(media,times(2)).extract(any(),any()); // rolled back terminal + successful terminal, never replay
            verify(sqs,times(2)).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals(1,count("processing_inbox"));
        } finally { jdbc.execute("ALTER TABLE processing_outbox DROP CONSTRAINT IF EXISTS reject_failed_test"); }
    }

    @Test void activeDuplicateAndDependencyOutageDoNotAckOrSpendMediaAttempt() throws Exception {
        var request=request(); var sqs=mock(SqsClient.class); var media=mock(MediaGateway.class); var storage=mock(StoreProcessingResult.class);
        gateway.acquire(request);
        try (var scheduler=Executors.newSingleThreadScheduledExecutor(); var consumer=consumer(request,sqs,media,storage,scheduler)) {
            consumer.poll(); verifyNoInteractions(media,storage); verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            expire(request.videoId()); when(storage.download(any(),any())).thenThrow(new IOException("S3 unavailable"));
            consumer.poll(); verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals(0,jdbc.queryForObject("SELECT media_attempts FROM processing_jobs WHERE id=?",Integer.class,request.videoId()));
        }
    }

    @Test void thirdMediaAttemptPendingStorageCompletesAndAcksWithoutMoreMedia() throws Exception {
        var request=StorageFixtures.request(); JobGateway.Claim claim=null;
        for (int i=0;i<3;i++) {
            claim=gateway.acquire(request); gateway.beginMedia(request.videoId(),claim.job().token());
            if (i<2) expire(request.videoId());
        }
        var artifact=StorageFixtures.artifact(claim.job());
        gateway.prepareResult(request.videoId(),claim.job().token(),artifact); expire(request.videoId());
        var objects=mock(ObjectStorageGateway.class); when(objects.present(eq(artifact),any())).thenReturn(true);
        var sqs=mock(SqsClient.class); var media=mock(MediaGateway.class);
        try (var files=new LocalProcessingArtifacts(temporary.resolve("third-attempt"),1);
             var scheduler=Executors.newSingleThreadScheduledExecutor();
             var consumer=consumer(request,sqs,media,new StoreProcessingResult(gateway,objects,files),scheduler)) {
            when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(call->{
                assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingCompleted'",Integer.class));
                return DeleteMessageResponse.builder().build();
            });
            consumer.poll(); consumer.poll(); verify(sqs,times(2)).deleteMessage(any(DeleteMessageRequest.class));
            verifyNoInteractions(media); verify(objects,times(1)).present(eq(artifact),any());
            assertEquals(3,jdbc.queryForObject("SELECT media_attempts FROM processing_jobs WHERE id=?",Integer.class,request.videoId()));
        }
    }

    @Test void outboxClaimsFenceLatePublisherAndPreserveExactPayloadAcrossRetry() throws Exception {
        var r=request(); var claim=gateway.acquire(r); gateway.fail(r.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
        var outbox=context.getBean(ProcessingOutbox.class);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(outbox::claim); var second=pool.submit(outbox::claim);
            var a=first.get(10,TimeUnit.SECONDS).orElseThrow(); var b=second.get(10,TimeUnit.SECONDS).orElseThrow();
            assertNotEquals(a.id(),b.id()); assertTrue(outbox.claim().isEmpty());
            jdbc.update("UPDATE processing_outbox SET claim_until=clock_timestamp()-interval '1 second' WHERE event_id=?",a.id());
            var takeover=outbox.claim().orElseThrow(); assertEquals(a.id(),takeover.id());
            assertEquals(a.body(),takeover.body()); assertNotEquals(a.token(),takeover.token());
            outbox.published(a); outbox.retry(a);
            assertEquals(takeover.token(),jdbc.queryForObject("SELECT claim_token FROM processing_outbox WHERE event_id=?",UUID.class,a.id()));
            outbox.retry(takeover); assertTrue(outbox.claim().isEmpty());
            jdbc.update("UPDATE processing_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE event_id=?",a.id());
            var retry=outbox.claim().orElseThrow(); assertEquals(a.body(),retry.body()); outbox.published(retry); outbox.published(b);
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE published_at IS NOT NULL",Integer.class));
            assertTrue(outbox.claim().isEmpty());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void terminalReplayRecoversLostResultAfterRestartWithoutMediaOrNewEnvelope(boolean success) throws Exception {
        var request=request(); var claim=success ? prepare(request) : gateway.acquire(request);
        if(!success) gateway.beginMedia(request.videoId(),claim.job().token());
        var terminal=success ? gateway.complete(request.videoId(),claim.job().token())
                : gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
        var outbox=context.getBean(ProcessingOutbox.class);
        var first=outbox.claim().orElseThrow(); outbox.published(first);
        var second=outbox.claim().orElseThrow(); outbox.published(second);
        var original=jdbc.queryForMap("SELECT event_id,payload,occurred_at FROM processing_outbox WHERE event_version=?",terminal.version());
        assertTrue(outbox.claim().isEmpty()); // No pending transport obligation remains; simulate result retention loss.
        context.close(); startApplication(); outbox=context.getBean(ProcessingOutbox.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<JobGateway.Claim> replay=()->{start.await(); return gateway.acquire(request);};
            var a=pool.submit(replay); var b=pool.submit(replay); start.countDown();
            assertEquals(JobGateway.Disposition.TERMINAL,a.get(10,TimeUnit.SECONDS).disposition());
            assertEquals(JobGateway.Disposition.TERMINAL,b.get(10,TimeUnit.SECONDS).disposition());
        }
        var resend=outbox.claim().orElseThrow();
        assertEquals(original.get("event_id"),resend.id()); assertEquals(original.get("payload"),resend.body());
        assertTrue(outbox.claim().isEmpty()); // Started remains published.
        var replay=gateway.acquire(request); // Does not steal or invalidate the publisher's active claim.
        assertEquals(terminal.completedAt(),replay.job().completedAt()); assertEquals(terminal.expiresAt(),replay.job().expiresAt());
        assertEquals(terminal.version(),replay.job().version()); assertEquals(1,replay.job().mediaAttempts());
        outbox.retry(resend);
        var available=jdbc.queryForObject("SELECT available_at FROM processing_outbox WHERE event_id=?",java.sql.Timestamp.class,resend.id());
        gateway.acquire(request);
        assertEquals(available,jdbc.queryForObject("SELECT available_at FROM processing_outbox WHERE event_id=?",java.sql.Timestamp.class,resend.id()));
        jdbc.update("UPDATE processing_outbox SET available_at=clock_timestamp() WHERE event_id=?",resend.id());
        var retried=outbox.claim().orElseThrow(); assertEquals(resend.body(),retried.body()); outbox.published(retried);
        assertEquals(2,count("processing_outbox")); assertEquals(1,count("processing_attempts"));
    }

    @Test void rescheduleRollbackPreventsAckAndRetryPreservesTerminal() throws Exception {
        var request=request(); var claim=gateway.acquire(request);
        var terminal=gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
        jdbc.update("UPDATE processing_outbox SET published_at=clock_timestamp()");
        jdbc.execute("ALTER TABLE processing_outbox ADD CONSTRAINT reject_replay_test CHECK(published_at IS NOT NULL)");
        var sqs=mock(SqsClient.class); var media=mock(MediaGateway.class); var storage=mock(StoreProcessingResult.class);
        try(var scheduler=Executors.newSingleThreadScheduledExecutor(); var consumer=consumer(request,sqs,media,storage,scheduler)) {
            consumer.poll(); verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE published_at IS NOT NULL",Integer.class));
            jdbc.execute("ALTER TABLE processing_outbox DROP CONSTRAINT reject_replay_test");
            consumer.poll(); verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
            verifyNoInteractions(media,storage);
            assertEquals(terminal.version(),gateway.acquire(request).job().version());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE published_at IS NULL",Integer.class));
        } finally { jdbc.execute("ALTER TABLE processing_outbox DROP CONSTRAINT IF EXISTS reject_replay_test"); }
    }

    @Test void cleanupProtectsPendingAndWinningArtifactsAcrossLeaseTakeover() {
        var cleanup=context.getBean(ArtifactCleanupGateway.class);
        var request=request(); var first=prepare(request); UUID producer=first.job().token();
        assertFalse(cleanup.remoteAllowed(request.videoId(),producer)); assertFalse(cleanup.localAllowed(request.videoId(),producer));
        expire(request.videoId());
        assertFalse(cleanup.remoteAllowed(request.videoId(),producer)); assertFalse(cleanup.localAllowed(request.videoId(),producer));
        var next=gateway.acquire(request); gateway.complete(request.videoId(),next.job().token());
        assertFalse(cleanup.remoteAllowed(request.videoId(),producer)); assertTrue(cleanup.localAllowed(request.videoId(),producer));
        assertFalse(cleanup.remoteAllowed(request.videoId(),UUID.randomUUID())); assertTrue(cleanup.abandoned(10).isEmpty());
    }

    @Test void cleanupRotatesAbandonedIntentsAndKeepsThemForLateWrites() {
        var cleanup=context.getBean(ArtifactCleanupGateway.class);
        var a=request(); var first=prepare(a); gateway.discardMissingResult(a.videoId(),first.job().token());
        var b=request(); var second=prepare(b); gateway.discardMissingResult(b.videoId(),second.job().token());
        var candidates=cleanup.abandoned(1); assertEquals(1,candidates.size());
        UUID token=ResultKey.parse(candidates.getFirst().objectKey()).producer(); cleanup.checked(token);
        assertNotEquals(token,ResultKey.parse(cleanup.abandoned(1).getFirst().objectKey()).producer());
        assertEquals(2,cleanup.abandoned(10).size());
        assertTrue(cleanup.remoteAllowed(a.videoId(),first.job().token()));
        // Repeating cleanup remains safe when an expired PUT lands after a previous delete.
        cleanup.checked(first.job().token()); assertTrue(cleanup.remoteAllowed(a.videoId(),first.job().token()));
        var live=gateway.acquire(a); assertFalse(cleanup.localAllowed(a.videoId(),live.job().token()));
    }

    @Test void cleanupWaitsForAnIntentAlreadyCommittingInsteadOfUsingStaleState() throws Exception {
        var cleanup=context.getBean(ArtifactCleanupGateway.class); var request=request(); var claim=gateway.acquire(request);
        var id=request.videoId(); var producer=claim.job().token(); gateway.beginMedia(id,producer); expire(id);
        try(var connection=connection(); var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var update=connection.prepareStatement("UPDATE "+schema+".processing_jobs SET result_json=?,status='RESULT_PENDING_STORAGE' WHERE id=?")) {
                update.setString(1,JsonMapper.builder().build().writeValueAsString(artifact(claim.job()))); update.setObject(2,id); update.executeUpdate();
            }
            var started=new CountDownLatch(1);
            var decision=pool.submit(()->{ started.countDown(); return cleanup.remoteAllowed(id,producer); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class,()->decision.get(200,TimeUnit.MILLISECONDS)); }
            finally { connection.commit(); }
            assertFalse(decision.get(10,TimeUnit.SECONDS));
        }
    }

    @Test void uncertainPutAndLocalRestartRecoverThePersistedIntentWithoutAnotherMediaAttempt() throws Exception {
        var request=request(); var claim=gateway.acquire(request); var running=gateway.beginMedia(request.videoId(),claim.job().token());
        var objects=mock(ObjectStorageGateway.class); var media=mock(MediaGateway.LocalMediaResult.class);
        Path source=Files.write(temporary.resolve("source.zip"),StorageFixtures.BYTES);
        when(media.zip()).thenReturn(source); when(media.sizeBytes()).thenReturn((long)StorageFixtures.BYTES.length);
        when(media.sha256()).thenReturn(StorageFixtures.hash(StorageFixtures.BYTES)); when(media.frameCount()).thenReturn(1);
        var stored=new java.util.concurrent.atomic.AtomicBoolean();
        when(objects.present(any(),any())).thenAnswer(inv->stored.get());
        doAnswer(inv->{ assertEquals(1,count("processing_result_intents")); stored.set(true); throw new IOException("PUT outcome uncertain"); })
                .when(objects).store(any(),any(),any());
        Path root=temporary.resolve("artifacts");
        try(var files=new LocalProcessingArtifacts(root,1)) {
            var usecase=new StoreProcessingResult(gateway,objects,files);
            assertThrows(IOException.class,()->usecase.publish(running,media,()->true));
        }
        assertEquals("RESULT_PENDING_STORAGE",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
        expire(request.videoId()); var takeover=gateway.acquire(request);
        try(var reopened=new LocalProcessingArtifacts(root,1)) {
            assertTrue(reopened.find(takeover.job().result(),()->true).isPresent());
            var complete=new StoreProcessingResult(gateway,objects,reopened).recover(takeover.job(),()->true);
            assertEquals(JobStatus.COMPLETED,complete.status()); assertEquals(1,complete.mediaAttempts());
            assertEquals(running.token(),ResultKey.parse(complete.result().objectKey()).producer());
            assertEquals(complete.completedAt().plusSeconds(86400),complete.expiresAt());
        }
        verify(objects,times(1)).store(any(),any(),any()); verify(objects,never()).deleteAbandoned(any());
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
    }

    @Test void concurrentReceiversAcquireExactlyOneLease() throws Exception {
        var request=request(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<JobGateway.Claim> receive=()->{ start.await(); return gateway.acquire(request); };
            var a=pool.submit(receive); var b=pool.submit(receive); start.countDown();
            var claims=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,claims.stream().filter(c->c.disposition()==JobGateway.Disposition.ACQUIRED).count());
            assertEquals(1,claims.stream().filter(c->c.disposition()==JobGateway.Disposition.BUSY).count());
        }
        assertEquals(1,count("processing_attempts")); assertEquals(1,count("processing_inbox"));
        assertEquals(1,count("processing_outbox"));
    }

    @Test void expiredOwnerCannotHeartbeatOrCompleteAfterTakeover() {
        var request=request(); var first=prepare(request); expire(request.videoId());
        var next=gateway.acquire(request);
        assertEquals(2,next.job().attempt()); assertEquals(1,next.job().mediaAttempts());
        assertNotEquals(first.job().token(),next.job().token());
        assertThrows(IllegalStateException.class,()->gateway.heartbeat(request.videoId(),first.job().token()));
        assertThrows(IllegalStateException.class,()->gateway.complete(request.videoId(),first.job().token()));
        var result=gateway.complete(request.videoId(),next.job().token());
        assertEquals(first.job().token().toString(),result.result().objectKey().split("/")[3]);
        assertEquals(1,count("processing_result_intents"));
    }

    @Test void terminalCommitIncludesInboxAndStableOutboxForRedelivery() {
        var request=request(); var claim=prepare(request);
        var done=gateway.complete(request.videoId(),claim.job().token());
        assertEquals(JobStatus.COMPLETED,done.status());
        assertEquals(done.completedAt().plusSeconds(86400),done.expiresAt());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_inbox WHERE completed_at IS NOT NULL",Integer.class));
        String payload=jdbc.queryForObject("SELECT payload FROM processing_outbox WHERE event_type='ProcessingCompleted'",String.class);
        var envelope=JsonMapper.builder().build().readTree(payload);
        assertEquals(request.videoId().toString(),envelope.get("aggregateId").asText());
        assertEquals(request.ownerId().toString(),envelope.get("ownerId").asText());
        assertEquals(done.expiresAt(),Instant.parse(envelope.get("payload").get("expiresAt").asText()));
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
        assertEquals(payload,jdbc.queryForObject("SELECT payload FROM processing_outbox WHERE event_type='ProcessingCompleted'",String.class));
        assertEquals(1,count("processing_attempts")); assertEquals(2,count("processing_outbox"));
    }

    @Test void outboxFailureRollsBackTerminalStateAndInbox() {
        var request=request(); var claim=prepare(request);
        jdbc.execute("ALTER TABLE processing_outbox ADD CONSTRAINT test_reject_terminal CHECK (event_type <> 'ProcessingCompleted')");
        try {
            assertThrows(RuntimeException.class,()->gateway.complete(request.videoId(),claim.job().token()));
            assertEquals("RESULT_PENDING_STORAGE",jdbc.queryForObject("SELECT status FROM processing_jobs",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processing_inbox WHERE completed_at IS NOT NULL",Integer.class));
            assertEquals(1,count("processing_outbox"));
        } finally { jdbc.execute("ALTER TABLE processing_outbox DROP CONSTRAINT test_reject_terminal"); }
        gateway.complete(request.videoId(),claim.job().token());
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
    }

    @Test void failedMediaIsDurableAndRequiresNoMoreExecution() {
        var request=request(); var claim=gateway.acquire(request);
        gateway.beginMedia(request.videoId(),claim.job().token());
        gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
        var receipt=gateway.acquire(request);
        assertEquals(JobGateway.Disposition.TERMINAL,receipt.disposition());
        assertEquals(JobStatus.FAILED,receipt.job().status());
        assertNull(receipt.job().expiresAt());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingFailed'",Integer.class));
    }

    @Test void conflictingPayloadOrReusedEventIdDoesNotCreateAnotherJob() {
        var request=request(); gateway.acquire(request);
        var changed=new ProcessingRequest(request.eventId(),request.videoId(),request.ownerId(),request.correlationId(),NOW,
                request.bucket(),request.objectKey(),101,request.sha256(),request.originalName());
        assertThrows(IllegalArgumentException.class,()->gateway.acquire(changed));
        var other=request();
        var sameEvent=new ProcessingRequest(request.eventId(),other.videoId(),other.ownerId(),other.correlationId(),NOW,
                other.bucket(),other.objectKey(),other.sizeBytes(),other.sha256(),other.originalName());
        assertThrows(IllegalArgumentException.class,()->gateway.acquire(sameEvent));
        assertEquals(1,count("processing_jobs")); assertEquals(1,count("processing_inbox"));
        var replay=new ProcessingRequest(UUID.randomUUID(),request.videoId(),request.ownerId(),request.correlationId(),NOW,
                request.bucket(),request.objectKey(),request.sizeBytes(),request.sha256(),request.originalName());
        assertEquals(JobGateway.Disposition.BUSY,gateway.acquire(replay).disposition());
        assertEquals(2,count("processing_inbox")); assertEquals(1,count("processing_jobs"));
    }

    @Test void retryDelayDoesNotSpendMediaAttemptsAndThreeStartsSurviveCrash() {
        var request=request(); var claim=gateway.acquire(request);
        gateway.defer(request.videoId(),claim.job().token(),30);
        assertEquals(JobGateway.Disposition.BUSY,gateway.acquire(request).disposition());
        jdbc.update("UPDATE processing_jobs SET retry_at=clock_timestamp()-interval '1 second'");
        for(int attempt=0;attempt<3;attempt++) {
            claim=gateway.acquire(request); assertEquals(attempt,claim.job().mediaAttempts());
            gateway.beginMedia(request.videoId(),claim.job().token()); expire(request.videoId());
        }
        var last=gateway.acquire(request);
        assertThrows(IllegalStateException.class,()->gateway.beginMedia(request.videoId(),last.job().token()));
        gateway.fail(request.videoId(),last.job().token(),FailureCode.PROCESSING_TIMEOUT);
        assertEquals(3,jdbc.queryForObject("SELECT media_attempts FROM processing_jobs",Integer.class));
    }

    @Test void concurrentTerminalConfirmationHasOneWinnerAndOneEvent() throws Exception {
        var request=request(); var claim=prepare(request); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> finish=()->{
                start.await();
                try { gateway.complete(request.videoId(),claim.job().token()); return true; }
                catch(IllegalStateException expected) { return false; }
            };
            var a=pool.submit(finish); var b=pool.submit(finish); start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingCompleted'",Integer.class));
    }

    @Test void terminalWithoutOutboxCannotBeAcknowledgedAndResultIntentSurvivesRetries() {
        var request=request(); var claim=prepare(request);
        gateway.defer(request.videoId(),claim.job().token(),1);
        jdbc.update("UPDATE processing_jobs SET retry_at=clock_timestamp()-interval '1 second'");
        var resumed=gateway.acquire(request);
        assertEquals(JobStatus.RESULT_PENDING_STORAGE,resumed.job().status());
        assertEquals(1,resumed.job().mediaAttempts());
        gateway.complete(request.videoId(),resumed.job().token());
        jdbc.update("DELETE FROM processing_outbox WHERE event_type='ProcessingCompleted'");
        assertThrows(IllegalStateException.class,()->gateway.acquire(request));
    }

    @Test void gatewayCommitsBeforeReturningEvenInsideARollingBackCallerTransaction() {
        var request=request();
        var outer=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        outer.executeWithoutResult(tx->{
            var claim=gateway.acquire(request);
            gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
            tx.setRollbackOnly();
        });
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_inbox WHERE completed_at IS NOT NULL",Integer.class));
        assertEquals(2,count("processing_outbox"));
    }

    @Test void harnessStopsAckAfterRealTerminalCommitAndReplayDoesNotRunMedia() throws Exception {
        var request=request();
        Path control=temporary.resolve(UUID.randomUUID().toString());
        Files.createDirectories(control.resolve("allowed"));
        Files.createFile(control.resolve("allowed").resolve(request.videoId().toString()));
        Files.writeString(control.resolve("worker-a."+request.videoId()+".before-ack.arm"),"fail");
        var adapters=new br.com.fiap.fiapx.harness.HarnessAdapters(new br.com.fiap.fiapx.harness.FaultControl(
                control,"worker-a",java.time.Duration.ofSeconds(5)));
        var sqs=mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().messageId("harness-delivery").receiptHandle("private-receipt")
                        .body(WorkMessageDecoderTest.envelope(request)).build()).build());
        var media=mock(MediaGateway.class); var storage=mock(StoreProcessingResult.class);
        when(storage.download(any(),any())).thenReturn(temporary.resolve("input"));
        when(media.extract(any(),any())).thenThrow(new MediaFailure(FailureCode.INVALID_MEDIA));
        try(var scheduler=Executors.newSingleThreadScheduledExecutor();
            var consumer=new WorkConsumer((SqsClient)adapters.wrap(sqs),"https://sqs.us-east-1.amazonaws.com/123456789012/work",
                new WorkMessageDecoder(context.getBean(JsonMapper.class),request.bucket()),
                new ProcessWork(gateway,media,storage,limits()),gateway,limits(),scheduler,()->true)) {
            consumer.poll();
            verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingFailed'",Integer.class));
            assertTrue(Files.readString(control.resolve("events/worker-a.tsv")).contains("before-ack-reached"));
            consumer.poll();
            verify(sqs).deleteMessage(any(DeleteMessageRequest.class)); verify(media).extract(any(),any());
            assertEquals(1,jdbc.queryForObject("SELECT media_attempts FROM processing_jobs WHERE id=?",Integer.class,request.videoId()));
        }
    }

    @Test void harnessAfterPutPreservesSqlIntentAndResumesWithoutSecondPutOrMedia() throws Exception {
        var request=request(); var claim=gateway.acquire(request);
        var job=gateway.beginMedia(request.videoId(),claim.job().token());
        Path control=temporary.resolve(UUID.randomUUID().toString());
        Files.createDirectories(control.resolve("allowed"));
        Files.createFile(control.resolve("allowed").resolve(request.videoId().toString()));
        Files.writeString(control.resolve("worker-a."+request.videoId()+".after-put.arm"),"fail");
        var adapters=new br.com.fiap.fiapx.harness.HarnessAdapters(new br.com.fiap.fiapx.harness.FaultControl(
                control,"worker-a",java.time.Duration.ofSeconds(5)));
        var objects=mock(ObjectStorageGateway.class);
        var present=new java.util.concurrent.atomic.AtomicBoolean();
        when(objects.present(any(),any())).thenAnswer(call->present.get());
        doAnswer(call->{
            assertEquals("RESULT_PENDING_STORAGE",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingCompleted'",Integer.class));
            present.set(true); return null;
        }).when(objects).store(any(),any(),any());
        var files=mock(br.com.fiap.fiapx.processing.core.gateway.LocalArtifactsGateway.class);
        when(files.find(any(),any())).thenReturn(Optional.of(temporary.resolve("zip")));
        var storage=new StoreProcessingResult(gateway,(ObjectStorageGateway)adapters.wrap(objects),files);
        var media=mock(MediaGateway.LocalMediaResult.class);
        when(media.zip()).thenReturn(temporary.resolve("zip")); when(media.sizeBytes()).thenReturn(10L);
        when(media.sha256()).thenReturn("a".repeat(64)); when(media.frameCount()).thenReturn(1);
        assertThrows(IllegalStateException.class,()->storage.publish(job,media,()->true));
        assertEquals("RESULT_PENDING_STORAGE",jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id=?",String.class,request.videoId()));
        expire(request.videoId()); var resumed=gateway.acquire(request);
        assertEquals(JobStatus.COMPLETED,storage.recover(resumed.job(),()->true).status());
        verify(objects).store(any(),any(),any());
        assertEquals(1,jdbc.queryForObject("SELECT media_attempts FROM processing_jobs WHERE id=?",Integer.class,request.videoId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_outbox WHERE event_type='ProcessingCompleted'",Integer.class));
    }
}
