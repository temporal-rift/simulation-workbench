package io.github.temporalrift.workbench.search.application.command;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.temporalrift.workbench.search.application.port.in.AdvanceCampaignsUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignEvaluationException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.HuntPlan;
import io.github.temporalrift.workbench.search.domain.campaign.HuntReport;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.CandidateGenerationExhaustedException;
import io.github.temporalrift.workbench.search.domain.candidate.CandidateGenerator;
import io.github.temporalrift.workbench.search.domain.candidate.SearchSeeds;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.HuntObjective;
import io.github.temporalrift.workbench.search.domain.round.Assessment;
import io.github.temporalrift.workbench.search.domain.round.RoundDecider;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.Page;

/**
 * Moves campaigns forward one persisted step at a time. A step is a pure function of what the campaign has recorded,
 * so a restart repeats whatever its last step left undone and gets the same answer: the experiment and the run of a
 * wave are requested with keys derived from the campaign, and a step applies, with everything it records, only if the
 * campaign is still at the version the step read.
 *
 * <ol>
 *   <li>A queued campaign starts on its next step.
 *   <li>A running campaign records the active time since its last step.
 *   <li>A round plays the seeds its candidates lack in waves one after another and, when every game is played,
 *       decides the whole round at once in candidate order. Round 0 generates its candidates; under version 1 every
 *       later round generates more, under version 2 none does.
 *   <li>Version 2 ends when its plan does: after the round that keeps at most {@code shortListSize} candidates. Version
 *       1 ends when its recorded budget is spent, when nothing survives, or when the short list has stayed the same
 *       through two rounds.
 * </ol>
 *
 * A campaign whose search is complete and whose short list is being confirmed on fresh seeds is stepped by
 * {@link ConfirmationProgress}.
 */
public class CampaignProgress implements AdvanceCampaignsUseCase {

    static final String PLAY_REFUSED = "PLAY_REFUSED";
    static final String RUN_FAILED = "RUN_FAILED";
    static final String RUN_CANCELLED = "RUN_CANCELLED";
    static final String RUN_MISSING = "RUN_MISSING";
    static final String EVALUATION_FAILED = "EVALUATION_FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(CampaignProgress.class);

    private static final int RECOVERY_ATTEMPTS = 5;
    private static final int LOG_PAGE = 200;

    private final CampaignRepository campaigns;
    private final CampaignExperiments experiments;
    private final CampaignRuns runs;
    private final CandidateEvaluation evaluation;
    private final CampaignSettings settings;
    private final Clock clock;
    private final WaveRuns waveRuns;
    private final ConfirmationProgress confirmation;
    private final HuntObjective objective;

    public CampaignProgress(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CampaignRuns runs,
            CandidateEvaluation evaluation,
            CampaignSettings settings,
            Clock clock) {
        this(campaigns, experiments, runs, evaluation, HuntObjective.UNAVAILABLE, settings, clock);
    }

    public CampaignProgress(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CampaignRuns runs,
            CandidateEvaluation evaluation,
            HuntObjective objective,
            CampaignSettings settings,
            Clock clock) {
        this.objective = objective;
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.runs = runs;
        this.evaluation = evaluation;
        this.settings = settings;
        this.clock = clock;
        this.waveRuns = new WaveRuns(campaigns, runs);
        this.confirmation = new ConfirmationProgress(campaigns, runs, evaluation, objective, waveRuns, clock);
    }

    @Override
    public boolean advance() {
        var advanced = false;
        for (var stored :
                campaigns.inStates(List.of(CampaignState.QUEUED, CampaignState.RUNNING, CampaignState.CONFIRMING))) {
            try {
                advanced |= step(stored);
            } catch (RuntimeException e) {
                // One campaign that cannot be stepped must not starve the ones behind it.
                LOG.error(
                        "The campaign {} could not be advanced",
                        stored.campaign().campaignId(),
                        e);
            }
            if (advanced) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void recover() {
        for (var stored : campaigns.inStates(List.of(CampaignState.RUNNING))) {
            var current = Optional.of(stored);
            for (var attempt = 0; attempt < RECOVERY_ATTEMPTS && current.isPresent(); attempt++) {
                var now = clock.instant();
                var applied = campaigns.apply(
                        CampaignChange.of(current.get(), current.get().campaign(), now));
                current = applied
                        ? Optional.empty()
                        : campaigns.find(stored.campaign().campaignId());
            }
        }
    }

    private boolean step(StoredCampaign stored) {
        return switch (stored.campaign().state()) {
            case QUEUED -> queued(stored);
            case RUNNING -> running(stored);
            case CONFIRMING -> confirmation.step(stored);
            default -> false;
        };
    }

    /** A queued campaign starts now; a failure it was stored with from an earlier version is cleared. */
    private boolean queued(StoredCampaign stored) {
        var now = clock.instant();
        return campaigns.apply(CampaignChange.of(stored, stored.campaign().start(now), now));
    }

    private boolean running(StoredCampaign stored) {
        var now = clock.instant();
        if (stored.campaign().startedAt() == null) {
            return begin(stored, now);
        }
        var step = new Step(stored, now);
        var campaignId = step.campaign.campaignId();
        waveRuns.reconcile(campaignId);
        try {
            return advanceRound(step);
        } catch (CampaignPlayRefusedException | IllegalArgumentException e) {
            return fail(step, PLAY_REFUSED, e.getMessage());
        } catch (CampaignEvaluationException e) {
            return fail(step, EVALUATION_FAILED, e.getMessage());
        } catch (CandidateGenerationExhaustedException e) {
            return fail(step, CampaignFailure.CANDIDATE_GENERATION_EXHAUSTED, e.getMessage());
        }
    }

    /** A campaign resumed before it ever started records now as its start. */
    private boolean begin(StoredCampaign stored, Instant now) {
        return campaigns.apply(CampaignChange.of(stored, stored.campaign().start(now), now));
    }

    private boolean advanceRound(Step step) {
        var campaign = step.campaign;
        var round = campaign.round();
        var ledger = Ledger.read(campaigns, campaign.campaignId());
        if (generates(campaign.definition().algorithm(), round)
                && ledger.generatedIn(round).isEmpty()) {
            return budgetSpent(step) ? concludeOutOfBudget(step, ledger) : generate(step, ledger);
        }
        var waiting = false;
        for (var wave : ledger.wavesOf(round)) {
            var progress = runs.progress(wave.runId());
            if (progress.isEmpty()) {
                return fail(step, RUN_MISSING, "The run " + wave.runId() + " of wave " + wave.index() + " is gone");
            }
            switch (progress.get().phase()) {
                case FAILED -> {
                    return fail(
                            step,
                            RUN_FAILED,
                            "Wave " + wave.index() + " failed: "
                                    + progress.get().failureMessage());
                }
                case CANCELLED -> {
                    return fail(step, RUN_CANCELLED, "Wave " + wave.index() + " was cancelled");
                }
                case ACTIVE, INTERRUPTED -> waiting = true;
                case COMPLETED -> {
                    // every game of the wave is played
                }
            }
        }
        if (waiting) {
            if (budgetSpent(step)) {
                return concludeOutOfBudget(step, ledger);
            }
            saveAccrual(step);
            return false;
        }
        var missing = missingGames(step, ledger);
        if (!missing.candidates.isEmpty()) {
            return budgetSpent(step) ? concludeOutOfBudget(step, ledger) : play(step, ledger, missing);
        }
        return decide(step, ledger);
    }

    /** Whether {@code round} generates candidates: every round of version 1, only round 0 of version 2. */
    private static boolean generates(SearchAlgorithm algorithm, int round) {
        return round == 0 || algorithm.version() == SearchAlgorithm.Version.SUCCESSIVE_HALVING_V1;
    }

    /** Only a version 1 campaign has a budget to spend; a version 2 one never runs out. */
    private boolean budgetSpent(Step step) {
        return step.campaign.budgetSpent();
    }

    private void saveAccrual(Step step) {
        if (!step.accruedAt.equals(step.stored.accruedAt())) {
            campaigns.apply(CampaignChange.of(step.stored, step.campaign, step.accruedAt));
        }
    }

    private boolean generate(Step step, Ledger ledger) {
        var campaign = step.campaign;
        var definition = campaign.definition();
        var generator = new CandidateGenerator(
                experiments.grid(definition),
                experiments.validation(definition),
                new SearchSeeds(definition.searchMasterSeed()),
                definition.algorithm());
        var round = campaign.round();
        var generated = round == 0
                ? generator.roundZero()
                : generator.laterRound(
                        round,
                        ledger.survivorsBefore(round),
                        ledger.candidates(),
                        ledger.candidates().size());
        var entries = new ArrayList<LogEntry>();
        for (var candidate : generated) {
            entries.add(new LogEntry(
                    ledger.nextSequence() + entries.size(),
                    round,
                    candidate.id(),
                    candidate.overrides(),
                    candidate.effectiveRulesDigest(),
                    List.of(),
                    0,
                    candidate.redraws(),
                    null,
                    LogEntry.Decision.GENERATED,
                    null,
                    null));
        }
        return campaigns.apply(step.change().recording(generated, entries));
    }

    /** The games a round still has to play for the candidates whose games match, and the seeds they lack. */
    private Missing missingGames(Step step, Ledger ledger) {
        var campaign = step.campaign;
        var allSeeds = seeds(campaign).gameSeeds(campaign.definition().seedsInRound(campaign.round()));
        var played = ledger.seedsByCandidate();
        var groups = new LinkedHashMap<List<String>, List<Candidate>>();
        for (var candidate : ledger.inPlay(campaign.round())) {
            var have = played.getOrDefault(candidate.id(), Set.of());
            var lacking = allSeeds.stream().filter(seed -> !have.contains(seed)).toList();
            if (!lacking.isEmpty()) {
                groups.computeIfAbsent(lacking, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        var first = groups.entrySet().stream().findFirst();
        return first.map(entry -> new Missing(entry.getValue(), entry.getKey()))
                .orElse(new Missing(List.of(), List.of()));
    }

    /** Starts the next wave of the round: a frozen experiment and a run of the games the candidates lack. */
    private boolean play(Step step, Ledger ledger, Missing missing) {
        var campaign = step.campaign;
        var definition = campaign.definition();
        var tables =
                definition.isHunt() ? List.<SeatTable>of() : evaluation.screeningTables(definition.criterionVersion());
        var casesPerGame = experiments.casesPerGame(definition, tables);
        // A wave holds at most the configured number of cases: first as many seeds as fit, then as many candidates.
        var seeds = missing.seeds.subList(
                0, Math.clamp(settings.maxCasesPerWave() / casesPerGame, 1, missing.seeds.size()));
        var perWave = Math.max(1, settings.maxCasesPerWave() / (casesPerGame * seeds.size()));
        var candidates = missing.candidates.stream().limit(perWave).toList();
        var index = ledger.waves().size();
        var experimentId =
                experiments.freeze(key(campaign, "experiment", index), definition, index, candidates, seeds, tables);
        var runId = runs.start(experimentId, key(campaign, "run", index));
        runs.setConcurrency(runId, WaveRuns.HELD);
        var games = new ArrayList<Game>();
        candidates.forEach(candidate -> seeds.forEach(seed -> games.add(new Game(candidate.id(), seed, index))));
        var applied =
                campaigns.apply(step.change().playing(new Wave(index, campaign.round(), experimentId, runId), games));
        if (applied) {
            waveRuns.reconcile(campaign.campaignId());
        }
        return applied;
    }

    /** Decides the round now that every game is played, in candidate order, then goes on or stops. */
    private boolean decide(Step step, Ledger ledger) {
        var campaign = step.campaign;
        var definition = campaign.definition();
        var algorithm = definition.algorithm();
        var round = campaign.round();
        var seeds = seeds(campaign).gameSeeds(definition.seedsInRound(round));
        var assessments = assess(step, ledger, seeds);
        if (definition.isHunt()) {
            return decideHunt(step, ledger, assessments);
        }
        var outcome = RoundDecider.decide(assessments, algorithm, round, ledger.nextSequence());
        var result = new RoundResult(
                round,
                outcome.survivors().stream()
                        .map(survivor -> new RoundResult.Survivor(
                                survivor.candidate().id(), survivor.verdict().name(), survivor.score()))
                        .toList(),
                outcome.shortList());
        var entries = new ArrayList<>(outcome.entries());
        var shortLists = new ArrayList<>(
                ledger.rounds().stream().map(RoundResult::shortList).toList());
        shortLists.add(outcome.shortList());
        Campaign next;
        if (algorithm.version() == SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2) {
            if (outcome.survivors().size() <= algorithm.shortListSize() || round >= algorithm.lastRound()) {
                var closing = RoundDecider.concludePlan(
                        outcome.survivors(), algorithm, round, ledger.nextSequence() + entries.size());
                entries.addAll(closing);
                next = campaign.completeSearch(closing.stream()
                        .filter(entry -> entry.decision() == LogEntry.Decision.SHORT_LISTED)
                        .map(LogEntry::candidateId)
                        .toList());
            } else {
                next = campaign.nextRound();
            }
        } else if (outcome.survivors().isEmpty()) {
            next = campaign.completeSearch(List.of());
        } else if (RoundDecider.stable(shortLists)) {
            entries.addAll(RoundDecider.conclude(
                    outcome.survivors(),
                    List.of(),
                    algorithm,
                    RoundDecider.StopReason.SHORT_LIST_STABLE,
                    round,
                    ledger.nextSequence() + entries.size()));
            next = campaign.completeSearch(outcome.shortList());
        } else if (campaign.budgetSpent()) {
            entries.addAll(RoundDecider.conclude(
                    outcome.survivors(),
                    List.of(),
                    algorithm,
                    RoundDecider.StopReason.BUDGET_EXHAUSTED,
                    round,
                    ledger.nextSequence() + entries.size()));
            next = campaign.completeSearch(outcome.shortList());
        } else {
            next = campaign.nextRound();
        }
        return campaigns.apply(step.change(next).deciding(result, entries));
    }

    private List<Assessment> assess(Step step, Ledger ledger, List<String> seeds) {
        var campaign = step.campaign;
        var candidates = ledger.inPlay(campaign.round());
        if (campaign.definition().isHunt()) {
            return assessHunt(campaign, ledger, candidates, seeds);
        }
        var games = candidates.stream()
                .map(candidate ->
                        new CandidateEvaluation.CandidateGames(candidate.id(), wavesPlaying(ledger, candidate)))
                .toList();
        CandidateEvaluation.Screening screening;
        try {
            screening = evaluation.screen(campaign.definition().criterionVersion(), games);
        } catch (IllegalArgumentException e) {
            throw new CampaignEvaluationException(e.getMessage(), e);
        }
        // Version 1 never ranked by gate miss, so its entries keep recording none.
        var ranksByGateMiss =
                campaign.definition().algorithm().version() == SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2;
        var gamesSpent = seeds.size() * gamesPerSeed(campaign);
        return candidates.stream()
                .map(candidate -> {
                    var evaluated = screening.evaluations().get(candidate.id());
                    return new Assessment(
                            candidate,
                            seeds,
                            gamesSpent,
                            Assessment.Verdict.valueOf(evaluated.verdict()),
                            evaluated.score(),
                            evaluated.failingGateLineIds(),
                            ranksByGateMiss ? evaluated.gateMiss() : null,
                            screening.dominators().getOrDefault(candidate.id(), List.of()));
                })
                .toList();
    }

    /**
     * A hunt scores each candidate by the win ratio of the hunted faction over the games of the round's seeds, which
     * lower is better as score, so the ranking is by ratio, best first. A hunt has no gate and no dominance.
     */
    private List<Assessment> assessHunt(
            Campaign campaign, Ledger ledger, List<Candidate> candidates, List<String> seeds) {
        var definition = campaign.definition();
        var gamesSpent = seeds.size() * HuntPlan.casesPerSeed(definition.huntPlayerCount());
        return candidates.stream()
                .map(candidate -> {
                    var tally = tallyOf(definition, ledger, candidate, seeds);
                    // A failed game is final, so a run can complete with some games missing. A candidate with only
                    // part of its games would be ranked on a few outcomes, so it is invalid and ranks last.
                    if (tally.games() != gamesSpent) {
                        return new Assessment(
                                candidate,
                                seeds,
                                gamesSpent,
                                Assessment.Verdict.INVALID,
                                null,
                                List.of(),
                                null,
                                List.of());
                    }
                    return new Assessment(
                            candidate,
                            seeds,
                            gamesSpent,
                            Assessment.Verdict.UNCONFIRMED,
                            -tally.ratio(),
                            List.of(),
                            0.0,
                            List.of());
                })
                .toList();
    }

    private HuntObjective.Tally tallyOf(
            CampaignDefinition definition, Ledger ledger, Candidate candidate, List<String> seeds) {
        HuntObjective.Tally total = null;
        for (var games : wavesPlaying(ledger, candidate)) {
            var part = objective.tally(games.runId(), candidate.id(), definition.faction(), seeds);
            total = total == null ? part : total.plus(part);
        }
        if (total == null) {
            throw new CampaignEvaluationException("Candidate " + candidate.id() + " has no games", null);
        }
        return total;
    }

    /**
     * Decides a round of a hunt: rounds 0 and 1 keep the best quarter, and round 2 keeps and short-lists all of its
     * four candidates. A best candidate whose win ratio over its last round reaches the flag factor goes on to a
     * confirmation; any other ends the cell unconfirmed.
     */
    private boolean decideHunt(Step step, Ledger ledger, List<Assessment> assessments) {
        var campaign = step.campaign;
        var definition = campaign.definition();
        var algorithm = definition.algorithm();
        var round = campaign.round();
        var last = round >= HuntPlan.LAST_ROUND;
        var outcome = last
                ? RoundDecider.decideKeepingAll(assessments, algorithm, round, ledger.nextSequence())
                : RoundDecider.decide(assessments, algorithm, round, ledger.nextSequence());
        var result = new RoundResult(
                round,
                outcome.survivors().stream()
                        .map(survivor -> new RoundResult.Survivor(
                                survivor.candidate().id(), survivor.verdict().name(), survivor.score()))
                        .toList(),
                outcome.shortList());
        var entries = new ArrayList<>(outcome.entries());
        Campaign next;
        if (!last) {
            next = campaign.nextRound();
        } else {
            var closing = RoundDecider.concludePlan(
                    outcome.survivors(), algorithm, round, ledger.nextSequence() + entries.size());
            entries.addAll(closing);
            var shortList = closing.stream()
                    .filter(entry -> entry.decision() == LogEntry.Decision.SHORT_LISTED)
                    .map(LogEntry::candidateId)
                    .toList();
            var best = outcome.survivors().getFirst();
            var bestRatio = best.score() == null ? 0 : -best.score();
            var report = HuntReport.unconfirmed(
                    definition.faction(), definition.huntPlayerCount(), screening(campaign, entries, bestRatio));
            var flagged = bestRatio >= HuntPlan.FLAG_FACTOR * HuntPlan.FAIR_RATIO;
            next = flagged
                    ? campaign.completeSearch(shortList).withHuntReport(report)
                    : campaign.completeHuntWithoutConfirmation(shortList, report, clock.instant());
        }
        return campaigns.apply(step.change(next).deciding(result, entries));
    }

    /**
     * What the search measured: the round-0 win ratios of every candidate, on the same seeds, give the realized noise
     * (their standard deviation) and the share of candidates at or above the flag line.
     */
    private HuntReport.Screening screening(Campaign campaign, List<LogEntry> lastRound, double bestRatio) {
        var ratios = new ArrayList<Double>();
        var offset = 0;
        Page<LogEntry> page;
        do {
            page = campaigns.log(campaign.campaignId(), LOG_PAGE, offset);
            page.items().stream()
                    .filter(entry -> entry.round() == 0
                            && entry.evaluation() != null
                            && entry.evaluation().score() != null)
                    .forEach(entry -> ratios.add(-entry.evaluation().score()));
            offset += LOG_PAGE;
        } while (offset < page.total());
        var mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var variance = ratios.stream()
                        .mapToDouble(ratio -> (ratio - mean) * (ratio - mean))
                        .sum()
                / Math.max(1, ratios.size() - 1);
        var above =
                ratios.stream().filter(ratio -> ratio >= HuntPlan.FLAG_FACTOR).count();
        return new HuntReport.Screening(
                ratios.size(), bestRatio, Math.sqrt(variance), ratios.isEmpty() ? 0 : (double) above / ratios.size());
    }

    /**
     * The games one seed plays for one candidate on the campaign's screening tables, so a log entry's
     * {@code gamesSpent} follows from its definition and seeds, never from retried attempts.
     */
    private int gamesPerSeed(Campaign campaign) {
        var definition = campaign.definition();
        var tables =
                definition.isHunt() ? List.<SeatTable>of() : evaluation.screeningTables(definition.criterionVersion());
        return experiments.casesPerGame(definition, tables);
    }

    private List<CandidateEvaluation.WaveGames> wavesPlaying(Ledger ledger, Candidate candidate) {
        return ledger.games().stream()
                .filter(game -> game.candidateId().equals(candidate.id()))
                .map(Game::waveIndex)
                .distinct()
                .sorted()
                .map(index ->
                        new CandidateEvaluation.WaveGames(ledger.wave(index).runId(), candidate.id()))
                .toList();
    }

    /**
     * Ends the search because the budget is spent while a round is in play: the survivors of the last decided round
     * are short-listed, and the candidates of the round that was never decided are discarded unevaluated.
     */
    private boolean concludeOutOfBudget(Step step, Ledger ledger) {
        var campaign = step.campaign;
        var algorithm = campaign.definition().algorithm();
        var round = campaign.round();
        var gamesPerSeed = gamesPerSeed(campaign);
        var survivors = new ArrayList<Assessment>();
        if (round > 0) {
            var earlier = ledger.rounds().get(round - 1);
            var seeds = seeds(campaign).gameSeeds(campaign.definition().seedsInRound(round - 1));
            var byId = ledger.survivorsBefore(round);
            for (var index = 0; index < byId.size(); index++) {
                var survivor = earlier.survivors().get(index);
                survivors.add(new Assessment(
                        byId.get(index),
                        seeds,
                        seeds.size() * gamesPerSeed,
                        Assessment.Verdict.valueOf(survivor.verdict()),
                        survivor.score(),
                        List.of(),
                        null,
                        List.of()));
            }
        }
        var undecided = ledger.generatedIn(round).stream()
                .map(candidate -> {
                    var played = playedSeeds(campaign, ledger, candidate);
                    return new Assessment(
                            candidate,
                            played,
                            played.size() * gamesPerSeed,
                            Assessment.Verdict.UNCONFIRMED,
                            null,
                            List.of(),
                            null,
                            List.of());
                })
                .toList();
        var entries = RoundDecider.conclude(
                survivors,
                undecided,
                algorithm,
                RoundDecider.StopReason.BUDGET_EXHAUSTED,
                round,
                ledger.nextSequence());
        var next = campaign.completeSearch(RoundDecider.shortListOf(survivors, algorithm));
        var applied = campaigns.apply(step.change(next).recording(List.of(), entries));
        if (applied) {
            waveRuns.stop(campaign.campaignId());
        }
        return applied;
    }

    /** The seeds the candidate has played to the end, in the order of the seed sequence. */
    private List<String> playedSeeds(Campaign campaign, Ledger ledger, Candidate candidate) {
        var finished = new HashSet<String>();
        wavesPlaying(ledger, candidate)
                .forEach(games -> finished.addAll(runs.finishedSeeds(games.runId(), games.variantLabel())));
        var sequence = seeds(campaign).gameSeeds(campaign.definition().seedsInRound(campaign.round()));
        return sequence.stream().filter(finished::contains).toList();
    }

    private boolean fail(Step step, String code, String message) {
        var next = step.campaign.fail(new CampaignFailure(code, message), clock.instant());
        var applied = campaigns.apply(step.change(next));
        if (applied) {
            waveRuns.stop(step.campaign.campaignId());
        }
        return applied;
    }

    private static SearchSeeds seeds(Campaign campaign) {
        return new SearchSeeds(campaign.definition().searchMasterSeed());
    }

    private static UUID key(Campaign campaign, String purpose, int waveIndex) {
        return UUID.nameUUIDFromBytes(
                (campaign.campaignId() + ":wave-" + purpose + ":" + waveIndex).getBytes(StandardCharsets.UTF_8));
    }

    /** The games of one group of candidates that lack the same seeds. */
    private record Missing(List<Candidate> candidates, List<String> seeds) {}

    /** A campaign read for one step, with the active time since its last step already counted. */
    private final class Step {

        private final StoredCampaign stored;
        private final Campaign campaign;
        private final Instant accruedAt;

        Step(StoredCampaign stored, Instant now) {
            this.stored = stored;
            var settled = stored.settledAt(now);
            this.campaign = settled.campaign();
            this.accruedAt = settled.accruedAt();
        }

        CampaignChange change() {
            return change(campaign);
        }

        CampaignChange change(Campaign next) {
            return CampaignChange.of(stored, next, accruedAt);
        }
    }
}
