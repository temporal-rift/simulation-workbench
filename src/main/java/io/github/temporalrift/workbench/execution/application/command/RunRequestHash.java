package io.github.temporalrift.workbench.execution.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.shared.text.Sha256;

/** Canonical identity of a run command, compared when an idempotency key is replayed. */
final class RunRequestHash {

    private RunRequestHash() {}

    static String of(String operation, UUID target) {
        return Sha256.hexUtf8(operation + "|" + target);
    }
}
