package br.com.fiap.fiapx.processing.infrastructure.integration;

import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
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
        assertEquals(1,count("databasechangelog"));
        String checksum=jdbc.queryForObject("SELECT md5sum FROM databasechangelog",String.class);
        context.close(); startApplication();
        var replay=gateway.acquire(request);
        assertEquals(JobGateway.Disposition.BUSY,replay.disposition());
        assertEquals(claim.job().token(),replay.job().token());
        assertEquals(checksum,jdbc.queryForObject("SELECT md5sum FROM databasechangelog",String.class));
        assertEquals(1,count("processing_jobs")); assertEquals(1,count("processing_outbox"));
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
}
