package io.github.temporalrift.workbench.search.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;

@WorkbenchIntegrationTest
class CampaignRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private CampaignRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM campaign_round");
        jdbc.update("DELETE FROM campaign_log_entry");
        jdbc.update("DELETE FROM campaign_game");
        jdbc.update("DELETE FROM campaign_wave");
        jdbc.update("DELETE FROM campaign_candidate");
        jdbc.update("DELETE FROM campaign");
    }

    @Test
    void aCampaignComesBackAsItWasStoredAtVersionZero() {
        var queued = queued("first", NOW);
        store(queued, NOW);

        var stored = repository.find(queued.campaignId()).orElseThrow();

        assertThat(stored.campaign()).isEqualTo(queued);
        assertThat(stored.version()).isZero();
        assertThat(stored.accruedAt()).isEqualTo(NOW);
        assertThat(repository.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aWriteBumpsTheVersionAndPersistsEveryField() {
        var queued = queued("moving", NOW);
        store(queued, NOW);
        var later = NOW.plusSeconds(90);
        var next = queued.waiting(new CampaignFailure(CampaignFailure.FIDELITY_NOT_ESTABLISHED, "no passing record"))
                .withParallelism(7);

        assertThat(repository.save(next, later, 0)).isTrue();

        var stored = repository.find(queued.campaignId()).orElseThrow();
        assertThat(stored.campaign()).isEqualTo(next);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.accruedAt()).isEqualTo(later);
    }

    @Test
    void aWriteFromAStaleVersionStoresNothing() {
        var queued = queued("stale", NOW);
        store(queued, NOW);
        var running = queued.start(NOW);
        assertThat(repository.save(running, NOW, 0)).isTrue();

        assertThat(repository.save(queued.pause(), NOW, 0)).isFalse();

        var stored = repository.find(queued.campaignId()).orElseThrow();
        assertThat(stored.campaign().state()).isEqualTo(CampaignState.RUNNING);
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void ofTwoConcurrentStepsFromTheSameVersionExactlyOneApplies() throws Exception {
        var queued = queued("race", NOW);
        store(queued, NOW);
        var start = new CountDownLatch(1);
        var steps = List.<Callable<Boolean>>of(
                () -> {
                    start.await();
                    return repository.save(queued.pause(), NOW, 0);
                },
                () -> {
                    start.await();
                    return repository.save(queued.start(NOW), NOW, 0);
                });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = steps.stream().map(executor::submit).toList();
            start.countDown();
            var applied = futures.stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();

            assertThat(applied).isOne();
        }
        assertThat(repository.find(queued.campaignId()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void campaignsAreListedNewestFirstAndFilteredByState() {
        var older = queued("older", NOW);
        var newer = queued("newer", NOW.plusSeconds(10));
        store(older, NOW);
        store(newer, NOW);
        repository.save(older.start(NOW), NOW, 0);

        assertThat(repository.list(null, 10, 0).items())
                .extracting(c -> c.definition().name())
                .containsExactly("newer", "older");
        assertThat(repository.list(null, 1, 1).items())
                .extracting(c -> c.definition().name())
                .containsExactly("older");
        assertThat(repository.list(null, 1, 1).total()).isEqualTo(2);
        assertThat(repository.list(CampaignState.RUNNING, 10, 0).items())
                .extracting(c -> c.definition().name())
                .containsExactly("older");
        assertThat(repository.inStates(List.of(CampaignState.QUEUED, CampaignState.RUNNING)))
                .extracting(stored -> stored.campaign().definition().name())
                .containsExactly("older", "newer");
    }

    @Test
    void candidatesWavesAndGamesAreStoredOnceAndComeBackInOrder() {
        var campaign = queued("records", NOW);
        store(campaign, NOW);
        var id = campaign.campaignId();
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.a", new BigDecimal("1.50"));
        var candidates = List.of(
                new Candidate("c-0001", 0, List.of(1, 2), overrides, "b".repeat(64), 3),
                new Candidate("c-0000", 0, List.of(0, 0), new TreeMap<>(), "a".repeat(64), 0));
        repository.insertCandidates(id, candidates);
        repository.insertCandidates(id, candidates);
        var wave = new Wave(0, 0, UUID.randomUUID(), UUID.randomUUID());
        repository.insertWave(id, wave);
        repository.insertWave(id, wave);
        var games = List.of(new Game("c-0001", "42", 0), new Game("c-0000", "43", 0), new Game("c-0000", "42", 0));
        repository.insertGames(id, games);
        repository.insertGames(id, List.of(new Game("c-0000", "42", 0), new Game("c-0001", "44", 0)));

        assertThat(repository.candidates(id)).extracting(Candidate::id).containsExactly("c-0000", "c-0001");
        assertThat(repository.candidates(id).get(1).overrides().get("game.rules.a"))
                .isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(repository.candidates(id).get(1).point()).containsExactly(1, 2);
        assertThat(repository.candidates(id).get(1).redraws()).isEqualTo(3);
        assertThat(repository.waves(id)).containsExactly(wave);
        assertThat(repository.games(id))
                .containsExactly(
                        new Game("c-0000", "42", 0),
                        new Game("c-0000", "43", 0),
                        new Game("c-0001", "42", 0),
                        new Game("c-0001", "44", 0));
    }

    @Test
    void aStepAppliesEverythingItRecordsOrNothing() {
        var campaign = queued("steps", NOW);
        store(campaign, NOW);
        var id = campaign.campaignId();
        var running = repository.find(id).orElseThrow();
        var candidate = new Candidate("c-0000", 0, List.of(0), new TreeMap<>(), "a".repeat(64), 0);
        var wave = new Wave(0, 0, UUID.randomUUID(), UUID.randomUUID());
        var change = CampaignChange.of(running, campaign.start(NOW), NOW)
                .recording(List.of(candidate), List.of())
                .playing(wave, List.of(new Game("c-0000", "42", 0)))
                .deciding(
                        new RoundResult(
                                0, List.of(new RoundResult.Survivor("c-0000", "CONFIRMED", 1.5)), List.of("c-0000")),
                        List.of());

        assertThat(repository.apply(change)).isTrue();

        assertThat(repository.find(id).orElseThrow().campaign().state()).isEqualTo(CampaignState.RUNNING);
        assertThat(repository.candidates(id)).hasSize(1);
        assertThat(repository.waves(id)).containsExactly(wave);
        assertThat(repository.games(id)).hasSize(1);
        assertThat(repository.rounds(id))
                .containsExactly(new RoundResult(
                        0, List.of(new RoundResult.Survivor("c-0000", "CONFIRMED", 1.5)), List.of("c-0000")));
    }

    @Test
    void aStepFromAStaleVersionRecordsNothingAtAll() {
        var campaign = queued("stale", NOW);
        store(campaign, NOW);
        var id = campaign.campaignId();
        var stale = repository.find(id).orElseThrow();
        assertThat(repository.save(campaign.pause(), NOW, 0)).isTrue();
        var candidate = new Candidate("c-0000", 0, List.of(0), new TreeMap<>(), "a".repeat(64), 0);
        var change = CampaignChange.of(stale, campaign.start(NOW), NOW)
                .recording(List.of(candidate), List.of())
                .deciding(
                        new RoundResult(0, List.of(new RoundResult.Survivor("c-0000", "CONFIRMED", null)), List.of()),
                        List.of());

        assertThat(repository.apply(change)).isFalse();

        assertThat(repository.find(id).orElseThrow().campaign().state()).isEqualTo(CampaignState.PAUSED);
        assertThat(repository.candidates(id)).isEmpty();
        assertThat(repository.rounds(id)).isEmpty();
    }

    @Test
    void aGameCanNotBeRecordedForACandidateOrWaveThatDoesNotExist() {
        var campaign = queued("orphans", NOW);
        store(campaign, NOW);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> repository.insertGames(campaign.campaignId(), List.of(new Game("c-0000", "42", 0))));
    }

    @Test
    void theLogComesBackInSequenceOrderAsTheEntriesThatWereAppended() {
        var campaign = queued("log", NOW);
        store(campaign, NOW);
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.a", new BigDecimal("2"));
        var first = new LogEntry(
                1,
                0,
                "c-0000",
                overrides,
                "a".repeat(64),
                List.of("11", "29"),
                2,
                1,
                new LogEntry.Evaluation("REJECTED", null),
                LogEntry.Decision.DISCARDED,
                LogEntry.Reason.GATE_FAILS,
                LogEntry.Evidence.failingLines(List.of("G-01")));
        var second = new LogEntry(
                2,
                0,
                "c-0001",
                new TreeMap<>(),
                "b".repeat(64),
                List.of("11", "29"),
                2,
                0,
                new LogEntry.Evaluation("UNCONFIRMED", 1.25),
                LogEntry.Decision.DISCARDED,
                LogEntry.Reason.DOMINATED,
                LogEntry.Evidence.dominatedBy("c-0003"));
        var third = new LogEntry(
                3,
                0,
                "c-0003",
                new TreeMap<>(),
                "c".repeat(64),
                List.of("11"),
                1,
                0,
                null,
                LogEntry.Decision.KEPT,
                null,
                null);

        repository.appendLog(campaign.campaignId(), List.of(second, first));
        repository.appendLog(campaign.campaignId(), List.of(first, second, third));

        var page = repository.log(campaign.campaignId(), 2, 0);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).containsExactly(first, second);
        assertThat(repository.log(campaign.campaignId(), 2, 2).items()).containsExactly(third);
        assertThat(page.items().get(0).canonicalJson()).isEqualTo(first.canonicalJson());
    }

    private void store(Campaign campaign, Instant accruedAt) {
        assertThat(repository.create(UUID.randomUUID(), "h".repeat(64), campaign, accruedAt))
                .isInstanceOf(CampaignCreation.Created.class);
    }

    @Test
    void aKeyClaimedTwiceReturnsTheFirstCampaignAndItsRequestHashAndStoresNothingMore() {
        var key = UUID.randomUUID();
        var first = queued("claimed", NOW);
        var second = queued("claimed-again", NOW.plusSeconds(5));
        assertThat(repository.create(key, "a".repeat(64), first, NOW)).isInstanceOf(CampaignCreation.Created.class);

        var repeated = repository.create(key, "b".repeat(64), second, NOW);

        assertThat(repeated).isEqualTo(new CampaignCreation.Existing(new CampaignClaim(first, "a".repeat(64))));
        assertThat(repository.find(second.campaignId())).isEmpty();
        assertThat(repository.findClaim(key)).contains(new CampaignClaim(first, "a".repeat(64)));
        assertThat(repository.findClaim(UUID.randomUUID())).isEmpty();
    }

    @Test
    void ofTwoRequestsRacingForOneKeyExactlyOneCampaignIsStored() throws Exception {
        var key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("one", "two").stream()
                    .map(name -> executor.submit(() -> {
                        start.await();
                        return repository.create(key, "a".repeat(64), queued(name, NOW), NOW);
                    }))
                    .toList();
            start.countDown();
            var outcomes = new ArrayList<CampaignCreation>();
            for (var future : futures) {
                outcomes.add(future.get());
            }

            assertThat(outcomes)
                    .filteredOn(CampaignCreation.Created.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(CampaignCreation.Existing.class::isInstance)
                    .hasSize(1);
        }
        assertThat(repository.list(null, 10, 0).total()).isOne();
    }

    private static Campaign queued(String name, Instant createdAt) {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        var definition = new CampaignDefinition(
                name,
                baseline,
                1,
                1,
                mapper.createObjectNode().put("kind", "heuristic"),
                List.of(3, 4),
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(8, 2, 2, 2));
        return Campaign.queued(UUID.randomUUID(), definition, CampaignDefinition.DEFAULT_PARALLELISM, createdAt);
    }
}
