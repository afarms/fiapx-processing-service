--liquibase formatted sql
--changeset fiapx:001-processing-jobs
CREATE TABLE processing_jobs (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    token UUID,
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    media_attempts INTEGER NOT NULL DEFAULT 0 CHECK (media_attempts BETWEEN 0 AND 3),
    event_version BIGINT NOT NULL DEFAULT 0 CHECK (event_version >= 0),
    lease_until TIMESTAMPTZ,
    retry_at TIMESTAMPTZ,
    media_started BOOLEAN NOT NULL DEFAULT FALSE,
    result_json TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    CONSTRAINT job_status CHECK (status IN ('PENDING','RUNNING','RETRY_WAIT','RESULT_PENDING_STORAGE','COMPLETED','FAILED')),
    CONSTRAINT terminal_time CHECK ((status IN ('COMPLETED','FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT successful_result CHECK ((status = 'COMPLETED') = (expires_at IS NOT NULL)),
    CONSTRAINT completed_artifact CHECK (status <> 'COMPLETED' OR (result_json IS NOT NULL AND expires_at = completed_at + INTERVAL '24 hours')),
    CONSTRAINT failed_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT pending_artifact CHECK (status <> 'RESULT_PENDING_STORAGE' OR result_json IS NOT NULL),
    CONSTRAINT execution_owner CHECK (status = 'PENDING' OR (token IS NOT NULL AND attempt > 0 AND lease_until IS NOT NULL))
);
CREATE INDEX processing_jobs_owner ON processing_jobs(owner_id);
CREATE INDEX processing_jobs_recovery ON processing_jobs(status, lease_until, retry_at);

CREATE TABLE processing_inbox (
    event_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES processing_jobs(id),
    request_json TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX processing_inbox_job ON processing_inbox(job_id);

CREATE TABLE processing_attempts (
    attempt_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES processing_jobs(id),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    acquired_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    media_started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    UNIQUE(job_id, attempt)
);

CREATE TABLE processing_result_intents (
    attempt_id UUID PRIMARY KEY REFERENCES processing_attempts(attempt_id),
    job_id UUID NOT NULL REFERENCES processing_jobs(id),
    result_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX processing_result_intents_job ON processing_result_intents(job_id);

CREATE TABLE processing_outbox (
    event_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES processing_jobs(id),
    event_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN ('ProcessingStarted','ProcessingCompleted','ProcessingFailed')),
    payload TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    UNIQUE(job_id, event_version)
);
CREATE UNIQUE INDEX processing_one_terminal_event ON processing_outbox(job_id)
    WHERE event_type IN ('ProcessingCompleted','ProcessingFailed');
CREATE INDEX processing_outbox_pending ON processing_outbox(available_at, claim_until) WHERE published_at IS NULL;
