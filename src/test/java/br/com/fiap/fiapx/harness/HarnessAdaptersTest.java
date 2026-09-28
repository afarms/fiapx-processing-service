package br.com.fiap.fiapx.harness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import br.com.fiap.fiapx.processing.Fixtures;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

class HarnessAdaptersTest {
    @TempDir Path root;
    private FaultControl controls() { return new FaultControl(root, "worker-a", Duration.ofSeconds(2)); }
    private Path base(UUID id, String point) { return root.resolve("worker-a." + id + "." + point); }
    private void arm(UUID id, String point, String mode) throws Exception {
        Files.createDirectories(root.resolve("allowed"));
        Files.writeString(root.resolve("allowed").resolve(id.toString()), "");
        Files.writeString(Path.of(base(id, point) + ".arm"), mode);
    }

    @Test void pauseStopsAtBoundaryUntilReleasedAndCannotReplayAfterRestart() throws Exception {
        UUID id = UUID.randomUUID(); arm(id, "after-put", "pause");
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> call = executor.submit(() -> controls().hit("after-put", id));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(root.resolve("events/worker-a.tsv")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(root.resolve("events/worker-a.tsv")));
            assertFalse(call.isDone());
            Files.createFile(Path.of(base(id, "after-put") + ".release"));
            call.get(2, TimeUnit.SECONDS);
        }
        Files.delete(Path.of(base(id, "after-put") + ".release"));
        assertDoesNotThrow(() -> controls().hit("after-put", id));
    }

    @Test void missingManifestAndTimeoutCannotSilentlyContinue() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(Path.of(base(id, "before-ack") + ".arm"), "pause");
        assertThrows(IllegalStateException.class, () -> controls().hit("before-ack", id));
        arm(id, "before-ack", "pause");
        var shortControl = new FaultControl(root, "worker-a", Duration.ofMillis(30));
        assertThrows(IllegalStateException.class, () -> shortControl.hit("before-ack", id));
    }

    @Test void afterPutFaultOccursOnlyAfterSuccessfulStorageAndDoesNotRepeat() throws Exception {
        var artifact = new ResultArtifact("bucket", new ResultKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).objectKey(), 10, "a".repeat(64), 1);
        var storage = mock(ObjectStorageGateway.class);
        var wrapped = (ObjectStorageGateway) new HarnessAdapters(controls()).wrap(storage);
        UUID id = ResultKey.parse(artifact.objectKey()).job(); arm(id, "after-put", "fail");
        Path source = root.resolve("zip");
        assertThrows(IllegalStateException.class, () -> wrapped.store(artifact, source, () -> true));
        verify(storage).store(eq(artifact), eq(source), any());
        wrapped.store(artifact, source, () -> true);
        verify(storage, times(2)).store(eq(artifact), eq(source), any());
    }

    @Test void storageFailureDoesNotProduceFalsePutEvidence() throws Exception {
        var artifact = new ResultArtifact("bucket", new ResultKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).objectKey(), 10, "a".repeat(64), 1);
        var storage = mock(ObjectStorageGateway.class);
        doThrow(new java.io.IOException("unavailable")).when(storage).store(any(), any(), any());
        var wrapped = (ObjectStorageGateway) new HarnessAdapters(controls()).wrap(storage);
        UUID id = ResultKey.parse(artifact.objectKey()).job(); arm(id, "after-put", "pause");
        assertThrows(java.io.IOException.class, () -> wrapped.store(artifact, root.resolve("zip"), () -> true));
        assertFalse(Files.exists(Path.of(base(id, "after-put") + ".claimed")));
    }

    @Test void ackFaultTargetsCurrentReceiptOnlyAndKeepsReceiptOutOfEvidence() throws Exception {
        UUID id = UUID.randomUUID(); arm(id, "before-ack", "fail");
        var sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().body("{\"aggregateId\":\"" + id + "\"}").receiptHandle("private-receipt").build()).build());
        var wrapped = (SqsClient) new HarnessAdapters(controls()).wrap(sqs);
        wrapped.receiveMessage(ReceiveMessageRequest.builder().build());
        wrapped.deleteMessage(DeleteMessageRequest.builder().receiptHandle("different-receipt").build());
        var request = DeleteMessageRequest.builder().receiptHandle("private-receipt").build();
        assertThrows(IllegalStateException.class, () -> wrapped.deleteMessage(request));
        verify(sqs, never()).deleteMessage(request);
        wrapped.deleteMessage(request); verify(sqs).deleteMessage(request);
        assertFalse(Files.readString(root.resolve("events/worker-a.tsv")).contains("receipt"));
    }

    @Test void dependencyFaultPrecedesDownloadAndMediaTraceSurroundsActualCall() throws Exception {
        var request = Fixtures.request(); arm(request.videoId(), "before-download", "fail");
        var storage = mock(ObjectStorageGateway.class); var media = mock(MediaGateway.class);
        var adapters = new HarnessAdapters(controls());
        var wrapped = (ObjectStorageGateway) adapters.wrap(storage);
        assertThrows(IllegalStateException.class, () -> wrapped.download(request, root.resolve("input"), () -> true));
        verifyNoInteractions(storage);
        wrapped.download(request, root.resolve("input"), () -> true);
        when(media.extract(any(), any())).thenAnswer(call -> {
            String trace = Files.readString(root.resolve("events/worker-a.tsv"));
            assertTrue(trace.contains("media-start")); assertFalse(trace.contains("media-end"));
            throw new MediaFailure(FailureCode.INVALID_MEDIA);
        });
        assertThrows(MediaFailure.class, () -> ((MediaGateway) adapters.wrap(media)).extract(root.resolve("input"), () -> true));
        assertTrue(Files.readString(root.resolve("events/worker-a.tsv")).contains("media-end"));
    }
}
