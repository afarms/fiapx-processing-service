package br.com.fiap.fiapx.processing.infrastructure.storage;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static br.com.fiap.fiapx.processing.infrastructure.storage.StorageFixtures.*;

class S3ProcessingStorageTest {
    @TempDir Path temporary;
    final S3Client client = mock(S3Client.class);
    final ResultArtifact artifact = artifact(job().snapshot());
    final S3ProcessingStorage storage = new S3ProcessingStorage(client, artifact.bucket(), 1000000);
    static S3Exception error(int status, String code) {
        return (S3Exception) S3Exception.builder().statusCode(status).awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }
    void response(byte[] data, long length) {
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(length).build(), AbortableInputStream.create(new ByteArrayInputStream(data))));
    }
    @Test void downloadsVerifiedOriginalAndConditionallyStoresCompleteResult() throws Exception {
        var request = request(); response(BYTES, BYTES.length);
        Path input = temporary.resolve("original"); storage.download(request, input, () -> true);
        assertArrayEquals(BYTES, Files.readAllBytes(input));
        assertThrows(FileAlreadyExistsException.class, () -> storage.download(request, input, () -> true));
        storage.store(artifact, input, () -> true);
        var put = ArgumentCaptor.forClass(PutObjectRequest.class); var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(put.capture(), body.capture());
        assertEquals("*", put.getValue().ifNoneMatch()); assertEquals("application/zip", put.getValue().contentType());
        assertEquals(artifact.objectKey(), put.getValue().key()); assertEquals(artifact.bucket(), put.getValue().bucket());
        try (var uploaded = body.getValue().contentStreamProvider().newStream()) { assertArrayEquals(BYTES, uploaded.readAllBytes()); }
        assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(artifact.sha256())), put.getValue().checksumSHA256());
        assertTrue(storage.present(artifact, () -> true));
        storage.deleteAbandoned(artifact); verify(client).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void rejectsCorruptTruncatedOversizedOrInaccessibleObjectsWithoutKeepingPartialInput() throws Exception {
        var request = request(); Path target = temporary.resolve("original");
        for (byte[] bytes : List.of(new byte[BYTES.length], new byte[BYTES.length - 1], new byte[BYTES.length + 1])) {
            response(bytes, BYTES.length);
            assertThrows(IOException.class, () -> storage.download(request, target, () -> true)); assertFalse(Files.exists(target));
        }
        response(BYTES, BYTES.length + 1);
        assertThrows(IOException.class, () -> storage.present(artifact, () -> true));
        for (S3Exception error : List.of(error(403, "AccessDenied"), error(500, "InternalError"), error(404, "NoSuchBucket"),
                (S3Exception) S3Exception.builder().statusCode(404).build())) {
            doThrow(error).when(client).getObject(any(GetObjectRequest.class));
            assertThrows(IOException.class, () -> storage.present(artifact, () -> true));
        }
        doThrow(error(404, "NoSuchKey")).when(client).getObject(any(GetObjectRequest.class));
        assertFalse(storage.present(artifact, () -> true));
        assertThrows(IOException.class, () -> storage.download(request, target, () -> true)); assertFalse(Files.exists(target));
        doThrow(new IllegalStateException("network unavailable")).when(client).getObject(any(GetObjectRequest.class));
        assertThrows(IOException.class, () -> storage.present(artifact, () -> true));
    }
    @Test void neverOverwritesConflictsOrDeletesOnUncertainPut() throws Exception {
        Path input = Files.write(temporary.resolve("zip"), BYTES); response(BYTES, BYTES.length);
        for (int status : new int[]{409, 412}) {
            doThrow(error(status, "Conflict")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            assertDoesNotThrow(() -> storage.store(artifact, input, () -> true));
        }
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(error(404, "NoSuchKey"));
        assertThrows(IOException.class, () -> storage.store(artifact, input, () -> true));
        doThrow(error(500, "InternalError")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThrows(IOException.class, () -> storage.store(artifact, input, () -> true));
        doThrow(new IllegalStateException("connection reset")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThrows(IOException.class, () -> storage.store(artifact, input, () -> true));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(error(503, "SlowDown"));
        assertThrows(IOException.class, () -> storage.deleteAbandoned(artifact));
    }
    @Test void rejectsForeignBucketsKeysSizesAndLostOwnershipBeforeIo() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new S3ProcessingStorage(client, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new S3ProcessingStorage(client, artifact.bucket(), 0));
        assertThrows(IllegalArgumentException.class, () -> storage.present(new ResultArtifact("other-bucket", artifact.objectKey(),1,artifact.sha256(),1), () -> true));
        assertThrows(IllegalArgumentException.class, () -> storage.present(new ResultArtifact(artifact.bucket(), "originals/unsafe",1,artifact.sha256(),1), () -> true));
        assertThrows(IllegalArgumentException.class, () -> storage.present(new ResultArtifact(artifact.bucket(),artifact.objectKey(),1000001,artifact.sha256(),1), () -> true));
        assertThrows(IOException.class, () -> storage.present(artifact, () -> false));
        assertThrows(IOException.class, () -> storage.download(request(), temporary.resolve("absent"), () -> false));
        verifyNoInteractions(client);
    }
    @Test void abortsResponseWithoutDrainingAfterQuotaOrLeaseLoss() throws Exception {
        InputStream endless = mock(InputStream.class);
        when(endless.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0); Arrays.fill(b, (byte)1); return b.length;
        });
        var aborted = new java.util.concurrent.atomic.AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long)BYTES.length).build(),
                AbortableInputStream.create(endless, () -> aborted.set(true))));
        assertThrows(IOException.class, () -> storage.present(artifact, () -> true)); assertTrue(aborted.get());
        verify(endless, times(1)).read(any(byte[].class), anyInt(), anyInt());
    }
}
