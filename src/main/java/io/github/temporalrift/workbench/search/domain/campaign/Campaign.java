package io.github.temporalrift.workbench.search.domain.campaign;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One configuration search: its frozen definition and where it stands. Every transition returns the next value and
 * refuses the states it is not legal in; the transitions a designer asks for are idempotent, so repeating one that
 * already holds returns the campaign unchanged.
 *
 * @param parallelism games played at once, which may change while the campaign runs
 * @param spentSeconds active time spent so far
 * @param round the round being played, or the last one played once the search is complete
 * @param shortList the short-listed candidate ids; empty until the search completes
 * @param confirmation the confirmation of the short list on fresh seeds, or null until it starts
 * @param failure why the campaign has not started or stopped, when it has not
 */
public record Campaign(
        UUID campaignId,
        CampaignDefinition definition,
        int parallelism,
        CampaignState state,
        long spentSeconds,
        int round,
        List<String> shortList,
        Confirmation confirmation,
        CampaignFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public Campaign {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        shortList = List.copyOf(shortList);
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1");
        }
    }

    /** A campaign as accepted: queued, with nothing spent. */
    public static Campaign queued(UUID campaignId, CampaignDefinition definition, int parallelism, Instant now) {
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                CampaignState.QUEUED,
                0,
                0,
                List.of(),
                null,
                null,
                now,
                null,
                null);
    }

    /** Whether the search has spent all the active time it was given. */
    public boolean budgetSpent() {
        return spentSeconds >= definition.budgetSeconds();
    }

    /** Active time still available, never below 0. */
    public long remainingSeconds() {
        return Math.max(0, definition.budgetSeconds() - spentSeconds);
    }

    /** Pauses a queued or running campaign; pausing a paused one changes nothing. */
    public Campaign pause() {
        return switch (state) {
            case PAUSED -> this;
            case QUEUED, RUNNING -> with(CampaignState.PAUSED, startedAt, finishedAt, failure);
            default -> throw new InvalidCampaignStateException("pause", state);
        };
    }

    /** Resumes a paused campaign; resuming a running one changes nothing. */
    public Campaign resume() {
        return switch (state) {
            case RUNNING -> this;
            case PAUSED -> with(CampaignState.RUNNING, startedAt, finishedAt, null);
            default -> throw new InvalidCampaignStateException("resume", state);
        };
    }

    /** Cancels a campaign that is not finished; cancelling a cancelled one changes nothing. */
    public Campaign cancel(Instant now) {
        return switch (state) {
            case CANCELLED -> this;
            case COMPLETED, FAILED -> throw new InvalidCampaignStateException("cancel", state);
            default -> with(CampaignState.CANCELLED, startedAt, now, failure);
        };
    }

    /** Changes the games played at once, from any state that is not over. */
    public Campaign withParallelism(int next) {
        if (state.isTerminal()) {
            throw new InvalidCampaignStateException("setParallelism", state);
        }
        if (next < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1");
        }
        return new Campaign(
                campaignId,
                definition,
                next,
                state,
                spentSeconds,
                round,
                shortList,
                confirmation,
                failure,
                createdAt,
                startedAt,
                finishedAt);
    }

    /**
     * Starts the search: a queued campaign becomes running and no longer waits for anything, and a campaign that was
     * resumed before it ever started records now as its start.
     */
    public Campaign start(Instant now) {
        if (state == CampaignState.RUNNING && startedAt == null) {
            return with(state, now, finishedAt, null);
        }
        if (state != CampaignState.QUEUED) {
            throw new InvalidCampaignStateException("start", state);
        }
        return with(CampaignState.RUNNING, now, null, null);
    }

    /** Records why a queued campaign cannot start yet, without changing its state. */
    public Campaign waiting(CampaignFailure reason) {
        if (state != CampaignState.QUEUED) {
            throw new InvalidCampaignStateException("wait", state);
        }
        return with(state, startedAt, finishedAt, reason);
    }

    /** Puts a running campaign that never started back in the queue because it cannot start yet. */
    public Campaign requeue(CampaignFailure reason) {
        if (state != CampaignState.RUNNING || startedAt != null) {
            throw new InvalidCampaignStateException("requeue", state);
        }
        return with(CampaignState.QUEUED, null, null, reason);
    }

    /** Counts {@code seconds} of active time; only a running campaign spends any. */
    public Campaign spend(long seconds) {
        if (state != CampaignState.RUNNING) {
            throw new InvalidCampaignStateException("spend", state);
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must not be negative");
        }
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                state,
                spentSeconds + seconds,
                round,
                shortList,
                confirmation,
                failure,
                createdAt,
                startedAt,
                finishedAt);
    }

    /** Moves on to the next round of a running search. */
    public Campaign nextRound() {
        if (state != CampaignState.RUNNING) {
            throw new InvalidCampaignStateException("nextRound", state);
        }
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                state,
                spentSeconds,
                round + 1,
                shortList,
                confirmation,
                failure,
                createdAt,
                startedAt,
                finishedAt);
    }

    /** Ends the search of a running campaign with its short list. */
    public Campaign completeSearch(List<String> shortListed) {
        if (state != CampaignState.RUNNING) {
            throw new InvalidCampaignStateException("completeSearch", state);
        }
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                CampaignState.SEARCH_COMPLETED,
                spentSeconds,
                round,
                shortListed,
                confirmation,
                failure,
                createdAt,
                startedAt,
                finishedAt);
    }

    /** Starts confirming a campaign whose search is complete. */
    public Campaign startConfirmation(Confirmation started) {
        if (state != CampaignState.SEARCH_COMPLETED) {
            throw new InvalidCampaignStateException("startConfirmation", state);
        }
        Objects.requireNonNull(started, "confirmation");
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                CampaignState.CONFIRMING,
                spentSeconds,
                round,
                shortList,
                started,
                failure,
                createdAt,
                startedAt,
                finishedAt);
    }

    /** Ends a confirming campaign with the evaluation of every confirmation run. */
    public Campaign completeConfirmation(Confirmation evaluated, Instant now) {
        if (state != CampaignState.CONFIRMING) {
            throw new InvalidCampaignStateException("completeConfirmation", state);
        }
        Objects.requireNonNull(evaluated, "confirmation");
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                CampaignState.COMPLETED,
                spentSeconds,
                round,
                shortList,
                evaluated,
                failure,
                createdAt,
                startedAt,
                now);
    }

    /** Stops a campaign that is not over because of an error. */
    public Campaign fail(CampaignFailure reason, Instant now) {
        if (state.isTerminal()) {
            throw new InvalidCampaignStateException("fail", state);
        }
        return with(CampaignState.FAILED, startedAt, now, reason);
    }

    private Campaign with(CampaignState next, Instant started, Instant finished, CampaignFailure reason) {
        return new Campaign(
                campaignId,
                definition,
                parallelism,
                next,
                spentSeconds,
                round,
                shortList,
                confirmation,
                reason,
                createdAt,
                started,
                finished);
    }
}
