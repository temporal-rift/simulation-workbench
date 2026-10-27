package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** The player ids of the bots every simulator slot mints, one per seat. */
public final class SimulatorBots {

    private SimulatorBots() {}

    /** The ids a simulator derives for {@code count} seats when its deployment declares none. */
    public static List<UUID> derived(int count) {
        return IntStream.range(0, count)
                .mapToObj(seat -> UUID.nameUUIDFromBytes(("simulator-bot-" + seat).getBytes(StandardCharsets.UTF_8)))
                .toList();
    }
}
