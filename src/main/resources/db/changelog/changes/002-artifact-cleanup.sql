--liquibase formatted sql
--changeset fiapx:002-artifact-cleanup
ALTER TABLE processing_result_intents ADD COLUMN cleanup_checked_at TIMESTAMPTZ;
CREATE INDEX processing_result_cleanup ON processing_result_intents(cleanup_checked_at NULLS FIRST, created_at, attempt_id);
