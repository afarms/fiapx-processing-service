package br.com.fiap.fiapx.processing.infrastructure.storage;

import java.io.*;
import java.security.*;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

final class ArtifactBytes {
    static void copy(InputStream input, OutputStream output, long expected, String hash, BooleanSupplier owned) throws IOException {
        var digest = sha256();
        long count = 0;
        byte[] buffer = new byte[8192];
        check(owned);
        for (int size; (size = input.read(buffer)) != -1;) {
            check(owned);
            if (size > expected - count) throw new IOException("Artifact exceeds expected size");
            count += size; digest.update(buffer, 0, size); output.write(buffer, 0, size);
        }
        check(owned);
        if (count != expected || !HexFormat.of().formatHex(digest.digest()).equals(hash))
            throw new IOException("Artifact integrity mismatch");
    }
    static void check(BooleanSupplier owned) throws IOException {
        if (Thread.currentThread().isInterrupted() || !owned.getAsBoolean()) throw new IOException("Storage execution lease lost");
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
