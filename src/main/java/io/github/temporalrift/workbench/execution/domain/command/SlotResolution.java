package io.github.temporalrift.workbench.execution.domain.command;

import java.util.Objects;

/** How a decision slot was settled: what the service answered, or what accepted state showed. */
public sealed interface SlotResolution {

    /** The status the slot takes once settled this way. */
    SlotStatus status();

    /** The service acknowledged the command. */
    record Accepted() implements SlotResolution {
        @Override
        public SlotStatus status() {
            return SlotStatus.ACCEPTED;
        }
    }

    /** The answer was lost, and accepted state later showed the service holds the command. */
    record Recovered() implements SlotResolution {
        @Override
        public SlotStatus status() {
            return SlotStatus.ACCEPTED;
        }
    }

    /** The answer was lost, and current accepted state showed the command was never spent. */
    record AbsentFromAcceptedState() implements SlotResolution {
        @Override
        public SlotStatus status() {
            return SlotStatus.NOT_SPENT;
        }
    }

    /** The service refused the command with the code. */
    record Rejected(String code) implements SlotResolution {
        public Rejected {
            Objects.requireNonNull(code, "code");
        }

        @Override
        public SlotStatus status() {
            return SlotStatus.NOT_SPENT;
        }
    }
}
