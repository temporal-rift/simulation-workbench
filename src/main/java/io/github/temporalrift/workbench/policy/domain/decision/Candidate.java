package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

/**
 * One syntactically valid choice for a decision window. The service stays authoritative for
 * legality; candidates are built only from published shapes and entitled state.
 */
public sealed interface Candidate {

    /** Canonical ordering rank: pass and decline options sort after every substantive choice. */
    int rank();

    /** Whether this is the pass or decline option that exhaustion falls back to. */
    default boolean isFallback() {
        return false;
    }

    /** Keep exactly these cards (ascending by id) from the dealt hand. */
    record KeepHand(List<UUID> cardInstanceIds) implements Candidate {
        public KeepHand {
            cardInstanceIds = List.copyOf(cardInstanceIds);
        }

        @Override
        public int rank() {
            return 0;
        }
    }

    record Declare(DeclarationMode mode, UUID eventId, UUID outcomeId) implements Candidate {
        @Override
        public int rank() {
            return 0;
        }
    }

    record Decline() implements Candidate {
        @Override
        public int rank() {
            return 9;
        }

        @Override
        public boolean isFallback() {
            return true;
        }
    }

    record PlayCard(UUID cardInstanceId, Target target) implements Candidate {
        @Override
        public int rank() {
            return 1;
        }
    }

    record PlaySpecial(SpecialAction action, Target target) implements Candidate {
        @Override
        public int rank() {
            return 2;
        }
    }

    record Pass() implements Candidate {
        @Override
        public int rank() {
            return 9;
        }

        @Override
        public boolean isFallback() {
            return true;
        }
    }

    record PlayParadoxCard(UUID cardInstanceId, Target.EventOutcome target) implements Candidate {
        @Override
        public int rank() {
            return 1;
        }
    }

    record PassParadox() implements Candidate {
        @Override
        public int rank() {
            return 9;
        }

        @Override
        public boolean isFallback() {
            return true;
        }
    }

    record ConfirmReady() implements Candidate {
        @Override
        public int rank() {
            return 0;
        }
    }
}
