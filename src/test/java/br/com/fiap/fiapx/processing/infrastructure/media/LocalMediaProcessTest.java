package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalMediaProcessTest {
    private static List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), Child.class.getName(), mode);
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "ok" -> System.out.print("ok");
                case "invalid" -> System.exit(1);
                case "oom" -> System.exit(137);
                case "closed" -> { System.out.close(); Thread.sleep(30000); }
                case "descendant" -> {
                    Process child = new ProcessBuilder(command("sleep")).start();
                    System.out.println(child.pid()); System.out.flush(); Thread.sleep(30000);
                }
                default -> Thread.sleep(30000);
            }
        }
    }
    @Test void readsStdoutAndClassifiesProcessExitsWithoutExposingStderr() throws Exception {
        var process = new LocalMediaProcess();
        process.run(command("ok"), MediaFixtures.budget(), in -> assertEquals("ok", new String(in.readAllBytes())));
        assertEquals(FailureCode.INVALID_MEDIA, assertThrows(MediaFailure.class,
                () -> process.run(command("invalid"), MediaFixtures.budget(), InputStream::readAllBytes)).code());
        assertThrows(IOException.class, () -> process.run(command("oom"), MediaFixtures.budget(), InputStream::readAllBytes));
        assertThrows(IOException.class, () -> process.run(command("oom"), MediaFixtures.budget(), in -> {
            in.readAllBytes(); throw new MediaFailure(FailureCode.INVALID_MEDIA);
        }));
        assertThrows(IOException.class, () -> process.run(List.of("no-such-media-binary-123"), MediaFixtures.budget(), InputStream::readAllBytes));
    }
    @Test void killsBlockedReadersAndProcessesOnTotalDeadlineAndLostOwnership() {
        var process = new LocalMediaProcess();
        for (String mode : List.of("sleep", "closed")) {
            long started = System.nanoTime();
            assertEquals(FailureCode.PROCESSING_TIMEOUT, assertThrows(MediaFailure.class,
                    () -> process.run(command(mode), new ExecutionBudget(Duration.ofMillis(600), () -> true), InputStream::readAllBytes)).code());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 6);
        }
        var owned = new AtomicBoolean(true);
        assertThrows(IllegalStateException.class, () -> process.run(command("sleep"),
                new ExecutionBudget(Duration.ofSeconds(10), owned::get), in -> { owned.set(false); in.readAllBytes(); }));
    }
    @Test void propagatesReaderErrorsAndThreadInterruption() {
        var process = new LocalMediaProcess();
        assertThrows(IOException.class, () -> process.run(command("sleep"), MediaFixtures.budget(), in -> { throw new IOException("disk"); }));
        assertThrows(MediaFailure.class, () -> process.run(command("sleep"), MediaFixtures.budget(), in -> { throw new MediaFailure(FailureCode.OUTPUT_LIMIT_EXCEEDED); }));
        assertThrows(IOException.class, () -> process.run(command("sleep"), MediaFixtures.budget(), in -> { throw new AssertionError("reader"); }));
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalStateException.class, () -> MediaFixtures.budget().check()); }
        finally { Thread.interrupted(); }
    }
    @Test void alsoTerminatesDescendantProcesses() throws Exception {
        var pid = new java.util.concurrent.atomic.AtomicLong();
        assertThrows(MediaFailure.class, () -> new LocalMediaProcess().run(command("descendant"),
                new ExecutionBudget(Duration.ofSeconds(2), () -> true), in -> {
                    var reader = new BufferedReader(new InputStreamReader(in));
                    pid.set(Long.parseLong(reader.readLine())); reader.readLine();
                }));
        assertTrue(pid.get() > 0);
        for (int i = 0; i < 20 && ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
    }
}
