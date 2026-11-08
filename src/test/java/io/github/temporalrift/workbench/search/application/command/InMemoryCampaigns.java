package io.github.temporalrift.workbench.search.application.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCommandClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.Page;

/** A campaign repository in memory with the same compare-and-set and insert-if-absent behaviour as the database. */
final class InMemoryCampaigns implements CampaignRepository {

    private final Map<UUID, StoredCampaign> campaigns = new LinkedHashMap<>();
    private final Map<UUID, CampaignClaim> claims = new LinkedHashMap<>();
    private final Map<UUID, CampaignCommandClaim> commands = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<String, Candidate>> candidates = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<Integer, Wave>> waves = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<String, Game>> games = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<Integer, LogEntry>> log = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<Integer, RoundResult>> rounds = new LinkedHashMap<>();

    @Override
    public synchronized CampaignCreation create(
            UUID idempotencyKey, String requestHash, Campaign queued, Instant accruedAt) {
        if (claims.containsKey(idempotencyKey)) {
            return new CampaignCreation.Existing(claims.get(idempotencyKey));
        }
        claims.put(idempotencyKey, new CampaignClaim(queued, requestHash));
        campaigns.put(queued.campaignId(), new StoredCampaign(queued, 0, accruedAt));
        return new CampaignCreation.Created();
    }

    @Override
    public synchronized Optional<CampaignClaim> findClaim(UUID idempotencyKey) {
        return Optional.ofNullable(claims.get(idempotencyKey));
    }

    @Override
    public synchronized Optional<CampaignCommandClaim> findCommand(UUID idempotencyKey) {
        return Optional.ofNullable(commands.get(idempotencyKey));
    }

    @Override
    public synchronized void claimCommand(UUID idempotencyKey, UUID campaignId, String requestHash, Instant claimedAt) {
        commands.putIfAbsent(idempotencyKey, new CampaignCommandClaim(campaignId, requestHash));
    }

    @Override
    public synchronized Optional<StoredCampaign> find(UUID campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }

    @Override
    public synchronized Page<StoredCampaign> list(CampaignState state, int limit, int offset) {
        var all = campaigns.values().stream()
                .filter(stored -> state == null || stored.campaign().state() == state)
                .toList();
        return Page.of(all, limit, offset);
    }

    @Override
    public synchronized List<StoredCampaign> inStates(List<CampaignState> states) {
        return campaigns.values().stream()
                .filter(stored -> states.contains(stored.campaign().state()))
                .toList();
    }

    @Override
    public synchronized boolean save(Campaign next, Instant accruedAt, long expectedVersion) {
        var current = campaigns.get(next.campaignId());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        campaigns.put(next.campaignId(), new StoredCampaign(next, expectedVersion + 1, accruedAt));
        return true;
    }

    @Override
    public synchronized boolean apply(CampaignChange change) {
        if (!save(change.next(), change.accruedAt(), change.expectedVersion())) {
            return false;
        }
        var id = change.next().campaignId();
        insertCandidates(id, change.candidates());
        change.waves().forEach(wave -> insertWave(id, wave));
        insertGames(id, change.games());
        appendLog(id, change.log());
        if (change.round() != null) {
            rounds.computeIfAbsent(id, key -> new TreeMap<>())
                    .putIfAbsent(change.round().round(), change.round());
        }
        return true;
    }

    @Override
    public synchronized List<RoundResult> rounds(UUID campaignId) {
        return new ArrayList<>(rounds.getOrDefault(campaignId, new TreeMap<>()).values());
    }

    @Override
    public synchronized void insertCandidates(UUID campaignId, List<Candidate> added) {
        var stored = candidates.computeIfAbsent(campaignId, key -> new TreeMap<>());
        added.forEach(candidate -> stored.putIfAbsent(candidate.id(), candidate));
    }

    @Override
    public synchronized List<Candidate> candidates(UUID campaignId) {
        return new ArrayList<>(
                candidates.getOrDefault(campaignId, new TreeMap<>()).values());
    }

    @Override
    public synchronized void insertWave(UUID campaignId, Wave wave) {
        waves.computeIfAbsent(campaignId, key -> new TreeMap<>()).putIfAbsent(wave.index(), wave);
    }

    @Override
    public synchronized List<Wave> waves(UUID campaignId) {
        return new ArrayList<>(waves.getOrDefault(campaignId, new TreeMap<>()).values());
    }

    @Override
    public synchronized void insertGames(UUID campaignId, List<Game> added) {
        var stored = games.computeIfAbsent(campaignId, key -> new TreeMap<>());
        added.forEach(game -> stored.putIfAbsent(game.candidateId() + "/" + game.seed(), game));
    }

    @Override
    public synchronized List<Game> games(UUID campaignId) {
        return new ArrayList<>(games.getOrDefault(campaignId, new TreeMap<>()).values());
    }

    @Override
    public synchronized void appendLog(UUID campaignId, List<LogEntry> entries) {
        var stored = log.computeIfAbsent(campaignId, key -> new TreeMap<>());
        entries.forEach(entry -> stored.putIfAbsent(entry.sequence(), entry));
    }

    @Override
    public synchronized java.util.Set<String> loggedAmong(UUID campaignId, java.util.Collection<String> seeds) {
        return log.getOrDefault(campaignId, new TreeMap<>()).values().stream()
                .flatMap(entry -> entry.seeds().stream())
                .filter(seeds::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public synchronized Page<LogEntry> log(UUID campaignId, int limit, int offset) {
        return Page.of(
                new ArrayList<>(log.getOrDefault(campaignId, new TreeMap<>()).values()), limit, offset);
    }

    /** The log as the canonical JSON the byte-identical guarantee is about. */
    synchronized List<String> canonicalLog(UUID campaignId) {
        return log.getOrDefault(campaignId, new TreeMap<>()).values().stream()
                .map(LogEntry::canonicalJson)
                .toList();
    }
}
