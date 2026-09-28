package br.com.fiap.fiapx.processing.infrastructure.integration;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import br.com.fiap.fiapx.processing.core.usecase.*;
import br.com.fiap.fiapx.processing.infrastructure.media.*;
import br.com.fiap.fiapx.processing.infrastructure.messaging.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox;
import br.com.fiap.fiapx.processing.infrastructure.storage.LocalProcessingArtifacts;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Invoked only by the cross-service local-flow target. AWS is replaced by files/mocks. */
class ProcessingFlowIT extends ProcessingPostgresIT {
    @Test void processProducedRequests() throws Exception {
        Path flow=Path.of(Objects.requireNonNull(System.getenv("FLOW_DIRECTORY"))).toAbsolutePath().normalize();
        var json=context.getBean(JsonMapper.class);
        var bodies=json.readValue(Files.readString(flow.resolve("requests.json")),String[].class);
        assertEquals(2,bodies.length);
        var limits=context.getBean(ProcessingLimits.class);
        var media=spy(new FfmpegMediaGateway(limits,temporary.resolve("media"),"/usr/bin/ffmpeg","/usr/bin/ffprobe",new LocalMediaProcess()));
        var objects=mock(ObjectStorageGateway.class);
        doAnswer(call->{
            ProcessingRequest request=call.getArgument(0); Path target=call.getArgument(1);
            Path source=object(flow,request.objectKey());
            assertEquals(request.sizeBytes(),Files.size(source)); assertEquals(request.sha256(),hash(source));
            Files.copy(source,target); return null;
        }).when(objects).download(any(),any(),any());
        when(objects.present(any(),any())).thenAnswer(call->Files.exists(object(flow,((ResultArtifact)call.getArgument(0)).objectKey())));
        doAnswer(call->{
            ResultArtifact artifact=call.getArgument(0); Path source=call.getArgument(1);
            assertEquals(artifact.sizeBytes(),Files.size(source)); assertEquals(artifact.sha256(),hash(source));
            Path target=object(flow,artifact.objectKey()); Files.createDirectories(target.getParent()); Files.copy(source,target);
            try(var zip=new ZipFile(target.toFile())) {
                assertEquals(2,zip.size()); assertEquals(2,artifact.frameCount());
                for(var entries=zip.entries();entries.hasMoreElements();) {
                    var entry=entries.nextElement(); assertTrue(entry.getName().matches("frame-\\d{6}\\.png"));
                    try(var stream=zip.getInputStream(entry)) { var image=ImageIO.read(stream); assertNotNull(image); assertEquals(64,image.getWidth()); assertEquals(48,image.getHeight()); }
                }
            }
            return null;
        }).when(objects).store(any(),any(),any());
        var sqs=mock(SqsClient.class);
        var sent=new ArrayList<String>();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(call->{ sent.add(((SendMessageRequest)call.getArgument(0)).messageBody()); return SendMessageResponse.builder().messageId(UUID.randomUUID().toString()).build(); });
        when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(call->{
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM processing_jobs WHERE status IN ('COMPLETED','FAILED')",Integer.class)>0);
            return DeleteMessageResponse.builder().build();
        });
        var decoder=new WorkMessageDecoder(json,"fiapx-media-test");
        var publisher=new ResultPublisher(context.getBean(ProcessingOutbox.class),sqs,"https://sqs.us-east-1.amazonaws.com/123456789012/events");
        try(var files=new LocalProcessingArtifacts(temporary.resolve("artifacts"),limits.diskReserveBytes());
            var scheduler=Executors.newSingleThreadScheduledExecutor();
            var consumer=new WorkConsumer(sqs,"https://sqs.us-east-1.amazonaws.com/123456789012/work",decoder,
                    new ProcessWork(gateway,media,new StoreProcessingResult(gateway,objects,files),limits),gateway,limits,scheduler,files::hasCapacity)) {
            for(String body:bodies) { delivery(sqs,body); consumer.poll(); }
            verify(sqs,times(2)).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_jobs WHERE status='COMPLETED'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processing_jobs WHERE status='FAILED' AND failure_code='INVALID_MEDIA'",Integer.class));
            publisher.dispatch(); assertEquals(4,sent.size());
            var original=List.copyOf(sent);
            // Simulated loss after publication: no pending outbox remains. Replay must regenerate only delivery, not events.
            sent.clear();
            for(String body:bodies) { delivery(sqs,body); consumer.poll(); }
            publisher.dispatch(); assertEquals(2,sent.size());
            assertTrue(original.containsAll(sent)); verify(media,times(2)).extract(any(),any());
            assertEquals(2,jdbc.queryForObject("SELECT sum(media_attempts) FROM processing_jobs",Integer.class));
            assertEquals(4,count("processing_outbox"));
            Files.writeString(flow.resolve("results.json"),json.writeValueAsString(original));
            Files.writeString(flow.resolve("replayed-results.json"),json.writeValueAsString(sent));
        }
    }
    private void delivery(SqsClient sqs,String body) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().body(body).receiptHandle(UUID.randomUUID().toString()).messageId(UUID.randomUUID().toString()).build()).build());
    }
    private Path object(Path flow,String key) {
        Path root=flow.resolve("objects"); Path path=root.resolve(key).normalize(); assertTrue(path.startsWith(root)); return path;
    }
    private String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
}
