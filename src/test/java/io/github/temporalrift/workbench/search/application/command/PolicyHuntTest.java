package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignKind;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.HuntPlan;
import io.github.temporalrift.workbench.search.domain.campaign.HuntReport;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.HuntObjective;
import io.github.temporalrift.workbench.shared.game.Faction;

/** A policy hunt end to end over scripted games: its plan, its gate, its confirmation and its report. */
class PolicyHuntTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int PLAYERS = 5;
    private static final int FIRST_ROUND_SEEDS = HuntPlan.seedsInRound(0, PLAYERS);

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final SimulatedPlay play = new SimulatedPlay();

    /** The win ratio of a table by its id, and the ratio of the standard table, scripted per test. */
    private ToDoubleFunction<String> ratios = table -> 1.0;

    /** The games of a table a failed case left unplayed, scripted per test. */
    private ToIntFunction<String> missingGames = table -> 0;

    /**
     * The games a table played: the seeds its run finished among those asked for, as the analysis counts them, so a
     * candidate that played in several waves adds its waves up. A confirmation plays all its seeds on both tables.
     */
    private final HuntObjective objective = (runId, tableId, faction, seeds) -> {
        var confirmation = HuntPlan.CANDIDATE_TABLE.equals(tableId) || HuntPlan.STANDARD_TABLE.equals(tableId);
        var playedSeeds = confirmation
                ? HuntPlan.confirmationSeeds(PLAYERS)
                : (int) play.finishedSeeds(runId, tableId).stream()
                        .filter(seeds::contains)
                        .count();
        var planned = playedSeeds * HuntPlan.casesPerSeed(PLAYERS);
        var wins = (int) Math.round(ratios.applyAsDouble(tableId) * planned / PLAYERS);
        var games = Math.max(0, planned - missingGames.applyAsInt(tableId));
        var ratio = games == 0 ? 0 : (double) wins / games * PLAYERS;
        return new HuntObjective.Tally(PLAYERS, games, Math.min(wins, games), ratio - 0.2, ratio + 0.2);
    };

    private final CampaignProgress progress =
            new CampaignProgress(campaigns, play, play, play, objective, new CampaignSettings(100_000), CLOCK);
    private final StartConfirmationCommandHandler start =
            new StartConfirmationCommandHandler(campaigns, play, play, play, CLOCK);

    private UUID create() {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        var definition = new CampaignDefinition(
                "weavers-5p",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(mapper.createObjectNode().put("id", "faction-spread")),
                List.of(PLAYERS),
                null,
                "7",
                "9",
                HuntPlan.algorithm(PLAYERS),
                CampaignKind.POLICY_HUNT,
                Faction.WEAVERS,
                1);
        var campaign = Campaign.queued(UUID.randomUUID(), definition, 4, NOW);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), campaign, NOW);
        return campaign.campaignId();
    }

    private Campaign campaign(UUID id) {
        return campaigns.find(id).orElseThrow().campaign();
    }

    private void drive(UUID id) {
        for (var step = 0; step < 5_000; step++) {
            var state = campaign(id).state();
            if (state.isTerminal() || state == CampaignState.SEARCH_COMPLETED) {
                return;
            }
            var moved = progress.advance();
            var played = play.playAllowed();
            if (!moved && !played) {
                return;
            }
        }
    }

    @Test
    void thePlanScreensSixtyFourThenSixteenThenFourCandidatesOnTheDocumentedGames() {
        var id = create();
        // candidates of the lowest ids score best, so the plan is followed whatever the scores are
        ratios = table -> 1.0 + 0.001 * (100 - Integer.parseInt(table.substring(2)));

        drive(id);

        var log = campaigns.log(id, 5_000, 0).items();
        for (var round = 0; round <= HuntPlan.LAST_ROUND; round++) {
            var seeds = HuntPlan.seedsInRound(round, PLAYERS);
            var decided = round;
            assertThat(log)
                    .filteredOn(entry -> entry.round() == decided && entry.evaluation() != null)
                    .allSatisfy(
                            entry -> assertThat(entry.gamesSpent()).isEqualTo(seeds * HuntPlan.casesPerSeed(PLAYERS)));
        }
        assertThat(log.stream()
                        .filter(entry -> entry.round() == 0 && entry.evaluation() != null)
                        .map(LogEntry::candidateId)
                        .distinct()
                        .count())
                .isEqualTo(64);
        assertThat(log.stream()
                        .filter(entry -> entry.round() == 1 && entry.evaluation() != null)
                        .map(LogEntry::candidateId)
                        .distinct()
                        .count())
                .isEqualTo(16);
        assertThat(log.stream()
                        .filter(entry -> entry.round() == 2 && entry.evaluation() != null)
                        .map(LogEntry::candidateId)
                        .distinct()
                        .count())
                .isEqualTo(4);
        assertThat(campaign(id).shortList()).hasSize(4);
        assertThat(log)
                .filteredOn(entry -> entry.decision() == LogEntry.Decision.SHORT_LISTED)
                .hasSize(4);
    }

    @Test
    void theRoundsPlayExactlyTheGamesOfTheDocumentedPlanOrTheNextWholeSeed() {
        assertThat(FIRST_ROUND_SEEDS * HuntPlan.casesPerSeed(PLAYERS)).isEqualTo(35);
        assertThat(HuntPlan.seedsInRound(1, PLAYERS) * HuntPlan.casesPerSeed(PLAYERS))
                .isEqualTo(130);
        assertThat(HuntPlan.seedsInRound(2, PLAYERS) * HuntPlan.casesPerSeed(PLAYERS))
                .isEqualTo(300);
        assertThat(HuntPlan.seedsInRound(1, 4) * HuntPlan.casesPerSeed(4)).isEqualTo(128);
        assertThat(HuntPlan.seedsInRound(2, 4) * HuntPlan.casesPerSeed(4)).isEqualTo(304);
        assertThat(HuntPlan.seedsInRound(2, 3) * HuntPlan.casesPerSeed(3)).isEqualTo(306);
    }

    @Test
    void aCellWhoseBestCandidateStaysBelowTheFlagLineIsUnconfirmedAndPlaysNoConfirmation() {
        var id = create();
        ratios = table -> 1.1;

        drive(id);

        var done = campaign(id);
        assertThat(done.state()).isEqualTo(CampaignState.COMPLETED);
        assertThat(done.confirmation()).isNull();
        assertThat(done.hunt().classification()).isEqualTo(HuntReport.Classification.NOT_CONFIRMED);
        assertThat(done.hunt().confirmation()).isNull();
        assertThat(done.hunt().limitations()).hasSize(2);
        assertThat(play.frozen()).noneMatch(experiment -> experiment.waveIndex() < 0);
    }

    @Test
    void theReportStatesTheRealizedNoiseAndTheShareOfCandidatesAboveTheFlagLine() {
        var id = create();
        // half of the candidates are at 1.5 and half at 1.0
        ratios = table -> Integer.parseInt(table.substring(2)) % 2 == 0 ? 1.5 : 1.0;

        drive(id);

        var screening = campaign(id).hunt().screening();
        assertThat(screening.candidates()).isEqualTo(64);
        assertThat(screening.shareAboveFlagLine()).isBetween(0.45, 0.55);
        assertThat(screening.noise()).isBetween(0.2, 0.3);
        assertThat(screening.screeningRatioOfBest()).isGreaterThanOrEqualTo(1.25);
    }

    @Test
    void aFlaggedCellConfirmsTheCandidateAndTheStandardSeatOnFreshSeedsAndClassifiesThem() {
        var id = create();
        ratios = table ->
                HuntPlan.CANDIDATE_TABLE.equals(table) ? 1.6 : HuntPlan.STANDARD_TABLE.equals(table) ? 1.0 : 1.4;
        drive(id);
        assertThat(campaign(id).state()).isEqualTo(CampaignState.SEARCH_COMPLETED);

        var confirming = start.handle(id, UUID.randomUUID());

        assertThat(confirming.state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(confirming.confirmation().runs()).hasSize(1);
        assertThat(confirming.confirmation().seedCount()).isEqualTo(HuntPlan.confirmationSeeds(PLAYERS));
        drive(id);
        var done = campaign(id);
        assertThat(done.state()).isEqualTo(CampaignState.COMPLETED);
        var report = done.hunt();
        assertThat(report.classification()).isEqualTo(HuntReport.Classification.EXPLOIT);
        assertThat(report.confirmation().candidateRatio()).isEqualTo(1.6);
        assertThat(report.confirmation().standardRatio()).isEqualTo(1.0);
        assertThat(report.confirmation().normalRatio()).isEqualTo(1.0);
        assertThat(report.confirmation().lower()).isLessThan(1.6);
        assertThat(report.confirmation().upper()).isGreaterThan(1.6);
    }

    @Test
    void aFlaggedCandidateThatIsOnlyAsGoodAsTheStandardSeatIsBalanceNoise() {
        var id = create();
        ratios = table ->
                HuntPlan.CANDIDATE_TABLE.equals(table) ? 1.1 : HuntPlan.STANDARD_TABLE.equals(table) ? 1.0 : 1.4;
        drive(id);
        start.handle(id, UUID.randomUUID());

        drive(id);

        assertThat(campaign(id).hunt().classification()).isEqualTo(HuntReport.Classification.BALANCE_NOISE);
    }

    @Test
    void aCandidateWithGamesMissingIsInvalidAndNeverFlagged() {
        var id = create();
        // c-0003 would be the best by far, but one of its games failed
        ratios = table -> "c-0003".equals(table) ? 4.0 : 1.0;
        missingGames = table -> "c-0003".equals(table) ? 1 : 0;

        drive(id);

        var log = campaigns.log(id, 5_000, 0).items();
        assertThat(log)
                .filteredOn(entry -> "c-0003".equals(entry.candidateId()) && entry.evaluation() != null)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.evaluation().verdict()).isEqualTo("INVALID"));
        assertThat(campaign(id).shortList()).doesNotContain("c-0003");
        assertThat(campaign(id).state()).isEqualTo(CampaignState.COMPLETED);
    }

    @Test
    void aConfirmationWithGamesMissingOnATableFailsInsteadOfClassifying() {
        var id = create();
        ratios = table ->
                HuntPlan.CANDIDATE_TABLE.equals(table) ? 1.6 : HuntPlan.STANDARD_TABLE.equals(table) ? 1.0 : 1.4;
        drive(id);
        start.handle(id, UUID.randomUUID());
        missingGames = table -> HuntPlan.STANDARD_TABLE.equals(table) ? 1 : 0;

        drive(id);

        var failed = campaign(id);
        assertThat(failed.state()).isEqualTo(CampaignState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("CONFIRMATION_EVALUATION_FAILED");
        assertThat(failed.hunt().classification()).isEqualTo(HuntReport.Classification.NOT_CONFIRMED);
    }

    @Test
    void aWaveWhereNoCandidateHasAllItsGamesFailsInsteadOfReportingNoExploit() {
        var id = create();
        missingGames = table -> 1;

        drive(id);

        var failed = campaign(id);
        assertThat(failed.state()).isEqualTo(CampaignState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("EVALUATION_FAILED");
        assertThat(failed.hunt()).isNull();
    }
}
