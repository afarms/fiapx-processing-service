package br.com.fiap.fiapx.processing.core.domain;

/** Sanitized media outcome; dependency, disk and lost-lease errors are deliberately not media failures. */
public final class MediaFailure extends RuntimeException {
    private final FailureCode code;
    public MediaFailure(FailureCode code) { super(code.name()); this.code = code; }
    public FailureCode code() { return code; }
}
