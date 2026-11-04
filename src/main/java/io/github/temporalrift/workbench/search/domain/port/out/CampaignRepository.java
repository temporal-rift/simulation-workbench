package io.github.temporalrift.workbench.search.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.Page;

/** Durable campaigns and everything a search records: candidates, waves, games and its decision log. */
public interface CampaignRepository {

    /**
     * Stores a new campaign at version 0 and claims the idempotency key in one step. When the key is already
     * claimed, even by a request racing this one, nothing is stored.
     */
    CampaignCreation create(UUID idempotencyKey, String requestHash, Campaign queued, Instant accruedAt);

    Optional<CampaignClaim> findClaim(UUID idempotencyKey);

    Optional<StoredCampaign> find(UUID campaignId);

    /** Campaigns newest first, filtered by state when given. */
    Page<Campaign> list(CampaignState state, int limit, int offset);

    /** The campaigns in {@code states}, oldest first. */
    List<StoredCampaign> inStates(List<CampaignState> states);

    /**
     * Stores the campaign's next value and bumps its version; false, with nothing stored, when the stored version is
     * no longer {@code expectedVersion}, because another step wrote first.
     */
    boolean save(Campaign next, Instant accruedAt, long expectedVersion);

    /**
     * Applies a step: the campaign's next value and everything the step records, all or nothing. False, with nothing
     * stored, when the campaign is no longer at the version the step started from. Records the campaign already has
     * are kept as they are.
     */
    boolean apply(CampaignChange change);

    /** What each decided round left standing, in round order. */
    List<RoundResult> rounds(UUID campaignId);

    /** Stores candidates the campaign does not have yet; ones it already has are left as they are. */
    void insertCandidates(UUID campaignId, List<Candidate> candidates);

    /** The campaign's candidates in generation order. */
    List<Candidate> candidates(UUID campaignId);

    void insertWave(UUID campaignId, Wave wave);

    /** The campaign's waves in play order. */
    List<Wave> waves(UUID campaignId);

    /** Stores games the campaign has not recorded yet; a game it already has, whichever wave plays it, is kept. */
    void insertGames(UUID campaignId, List<Game> games);

    /** The campaign's games in candidate then seed order. */
    List<Game> games(UUID campaignId);

    /** Appends entries to the decision log; an entry already stored at its sequence is left as it is. */
    void appendLog(UUID campaignId, List<LogEntry> entries);

    /** The log in sequence order. */
    Page<LogEntry> log(UUID campaignId, int limit, int offset);
}
