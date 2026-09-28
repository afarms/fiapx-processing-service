package br.com.fiap.fiapx.processing.infrastructure.messaging;

final class QueueAddress {
    private QueueAddress() {}
    static void validate(String url) {
        if (url==null || !url.matches("https://sqs\\.[a-z0-9-]+\\.amazonaws\\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Configure a Standard SQS queue URL");
    }
}
