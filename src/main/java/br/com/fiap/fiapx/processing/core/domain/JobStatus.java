package br.com.fiap.fiapx.processing.core.domain;

public enum JobStatus {
    PENDING, RUNNING, RETRY_WAIT, RESULT_PENDING_STORAGE, COMPLETED, FAILED;

    public boolean terminal() { return this == COMPLETED || this == FAILED; }
}
