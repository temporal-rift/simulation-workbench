package io.github.temporalrift.workbench.execution.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical identity of a run command, compared when an idempotency key is replayed. */
final class RunRequestHash {

    private RunRequestHash() {}

    static String of(String operation, UUID target) {
        try {
            var bytes = (operation + "|" + target).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
