package io.github.temporalrift.workbench.search.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;

/**
 * Campaigns and everything a search records, in the workbench database. A campaign write is a compare-and-set on its
 * row version; the records a search appends are insert-if-absent, so a step replayed after a crash stores nothing
 * twice.
 */
@Component
class CampaignRepositoryAdapter implements CampaignRepository {

    /** Keeps a statement under the driver's bind-parameter limit. */
    private static final int ROWS_PER_STATEMENT = 500;

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<RoundResult.Survivor>> SURVIVOR_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INTEGERS = new TypeReference<>() {};
    private static final TypeReference<TreeMap<String, String>> DECIMALS = new TypeReference<>() {};

    private static final Table<Record> CAMPAIGN = DSL.table(DSL.name("campaign"));
    private static final Field<UUID> CAMPAIGN_ID = DSL.field(DSL.name("campaign_id"), SQLDataType.UUID);
    private static final Field<UUID> KEY = DSL.field(DSL.name("idempotency_key"), SQLDataType.UUID);
    private static final Field<String> REQUEST_HASH = DSL.field(DSL.name("request_hash"), SQLDataType.VARCHAR);
    private static final Field<String> NAME = DSL.field(DSL.name("name"), SQLDataType.VARCHAR);
    private static final Field<String> DEFINITION = DSL.field(DSL.name("definition_json"), SQLDataType.CLOB);
    private static final Field<Integer> PARALLELISM = DSL.field(DSL.name("parallelism"), SQLDataType.INTEGER);
    private static final Field<String> STATE = DSL.field(DSL.name("state"), SQLDataType.VARCHAR);
    private static final Field<Long> SPENT = DSL.field(DSL.name("spent_seconds"), SQLDataType.BIGINT);
    private static final Field<Integer> ROUND = DSL.field(DSL.name("round"), SQLDataType.INTEGER);
    private static final Field<String> SHORT_LIST = DSL.field(DSL.name("short_list_json"), SQLDataType.CLOB);
    private static final Field<String> FAILURE_CODE = DSL.field(DSL.name("failure_code"), SQLDataType.VARCHAR);
    private static final Field<String> FAILURE_MESSAGE = DSL.field(DSL.name("failure_message"), SQLDataType.CLOB);
    private static final Field<OffsetDateTime> ACCRUED =
            DSL.field(DSL.name("accrued_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<Long> VERSION = DSL.field(DSL.name("row_version"), SQLDataType.BIGINT);
    private static final Field<OffsetDateTime> CREATED =
            DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> STARTED =
            DSL.field(DSL.name("started_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> FINISHED =
            DSL.field(DSL.name("finished_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private static final Table<Record> CANDIDATE = DSL.table(DSL.name("campaign_candidate"));
    private static final Field<String> CANDIDATE_ID = DSL.field(DSL.name("candidate_id"), SQLDataType.VARCHAR);
    private static final Field<String> POINT = DSL.field(DSL.name("point_json"), SQLDataType.CLOB);
    private static final Field<String> OVERRIDES = DSL.field(DSL.name("overrides_json"), SQLDataType.CLOB);
    private static final Field<String> DIGEST = DSL.field(DSL.name("effective_rules_digest"), SQLDataType.VARCHAR);
    private static final Field<Integer> REDRAWS = DSL.field(DSL.name("redraws"), SQLDataType.INTEGER);

    private static final Table<Record> WAVE = DSL.table(DSL.name("campaign_wave"));
    private static final Field<Integer> WAVE_INDEX = DSL.field(DSL.name("wave_index"), SQLDataType.INTEGER);
    private static final Field<UUID> EXPERIMENT = DSL.field(DSL.name("experiment_id"), SQLDataType.UUID);
    private static final Field<UUID> RUN = DSL.field(DSL.name("run_id"), SQLDataType.UUID);

    private static final Table<Record> GAME = DSL.table(DSL.name("campaign_game"));
    private static final Field<String> SEED = DSL.field(DSL.name("seed"), SQLDataType.VARCHAR);

    private static final Table<Record> ROUND_RESULT = DSL.table(DSL.name("campaign_round"));
    private static final Field<String> SURVIVORS = DSL.field(DSL.name("survivors_json"), SQLDataType.CLOB);

    private static final Table<Record> LOG = DSL.table(DSL.name("campaign_log_entry"));
    private static final Field<Integer> SEQUENCE = DSL.field(DSL.name("sequence"), SQLDataType.INTEGER);
    private static final Field<String> ENTRY = DSL.field(DSL.name("entry_json"), SQLDataType.CLOB);

    private final DSLContext dsl;
    private final StoredJson json;
    private final InsertOnce insertOnce;

    CampaignRepositoryAdapter(DSLContext dsl, StoredJson json, InsertOnce insertOnce) {
        this.dsl = dsl;
        this.insertOnce = insertOnce;
        this.json = json;
    }

    @Override
    public CampaignCreation create(UUID idempotencyKey, String requestHash, Campaign queued, Instant accruedAt) {
        var claimed = findClaim(idempotencyKey);
        if (claimed.isPresent()) {
            return new CampaignCreation.Existing(claimed.get());
        }
        var stored = insertOnce.insert(() -> insert(idempotencyKey, requestHash, queued, accruedAt));
        if (stored) {
            return new CampaignCreation.Created();
        }
        return new CampaignCreation.Existing(findClaim(idempotencyKey).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CampaignClaim> findClaim(UUID idempotencyKey) {
        return dsl.selectFrom(CAMPAIGN)
                .where(KEY.eq(idempotencyKey))
                .fetchOptional()
                .map(row -> new CampaignClaim(stored(row).campaign(), row.get(REQUEST_HASH)));
    }

    private void insert(UUID idempotencyKey, String requestHash, Campaign queued, Instant accruedAt) {
        var failure = queued.failure();
        dsl.insertInto(CAMPAIGN)
                .set(CAMPAIGN_ID, queued.campaignId())
                .set(KEY, idempotencyKey)
                .set(REQUEST_HASH, requestHash)
                .set(NAME, queued.definition().name())
                .set(DEFINITION, json.write(queued.definition()))
                .set(PARALLELISM, queued.parallelism())
                .set(STATE, queued.state().name())
                .set(SPENT, queued.spentSeconds())
                .set(ROUND, queued.round())
                .set(SHORT_LIST, json.write(queued.shortList()))
                .set(FAILURE_CODE, failure == null ? null : failure.code())
                .set(FAILURE_MESSAGE, failure == null ? null : failure.message())
                .set(ACCRUED, utc(accruedAt))
                .set(VERSION, 0L)
                .set(CREATED, utc(queued.createdAt()))
                .set(STARTED, utc(queued.startedAt()))
                .set(FINISHED, utc(queued.finishedAt()))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredCampaign> find(UUID campaignId) {
        return dsl.selectFrom(CAMPAIGN)
                .where(CAMPAIGN_ID.eq(campaignId))
                .fetchOptional()
                .map(this::stored);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoredCampaign> list(CampaignState state, int limit, int offset) {
        Condition filter = state == null ? DSL.trueCondition() : STATE.eq(state.name());
        var total = dsl.fetchCount(CAMPAIGN, filter);
        var items = dsl.selectFrom(CAMPAIGN)
                .where(filter)
                .orderBy(CREATED.desc(), CAMPAIGN_ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(this::stored);
        return new Page<>(items, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredCampaign> inStates(List<CampaignState> states) {
        return dsl.selectFrom(CAMPAIGN)
                .where(STATE.in(states.stream().map(Enum::name).toList()))
                .orderBy(CREATED.asc(), CAMPAIGN_ID.asc())
                .fetch(this::stored);
    }

    @Override
    @Transactional
    public boolean save(Campaign next, Instant accruedAt, long expectedVersion) {
        var failure = next.failure();
        return dsl.update(CAMPAIGN)
                        .set(PARALLELISM, next.parallelism())
                        .set(STATE, next.state().name())
                        .set(SPENT, next.spentSeconds())
                        .set(ROUND, next.round())
                        .set(SHORT_LIST, json.write(next.shortList()))
                        .set(FAILURE_CODE, failure == null ? null : failure.code())
                        .set(FAILURE_MESSAGE, failure == null ? null : failure.message())
                        .set(ACCRUED, utc(accruedAt))
                        .set(VERSION, expectedVersion + 1)
                        .set(STARTED, utc(next.startedAt()))
                        .set(FINISHED, utc(next.finishedAt()))
                        .where(CAMPAIGN_ID.eq(next.campaignId()))
                        .and(VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    @Transactional
    public boolean apply(CampaignChange change) {
        if (!save(change.next(), change.accruedAt(), change.expectedVersion())) {
            return false;
        }
        var campaignId = change.next().campaignId();
        insertCandidates(campaignId, change.candidates());
        change.waves().forEach(wave -> insertWave(campaignId, wave));
        insertGames(campaignId, change.games());
        appendLog(campaignId, change.log());
        if (change.round() != null) {
            insertRound(campaignId, change.round());
        }
        return true;
    }

    private void insertRound(UUID campaignId, RoundResult result) {
        dsl.insertInto(ROUND_RESULT)
                .set(CAMPAIGN_ID, campaignId)
                .set(ROUND, result.round())
                .set(SURVIVORS, json.write(result.survivors()))
                .set(SHORT_LIST, json.write(result.shortList()))
                .onConflictDoNothing()
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoundResult> rounds(UUID campaignId) {
        return dsl.selectFrom(ROUND_RESULT)
                .where(CAMPAIGN_ID.eq(campaignId))
                .orderBy(ROUND.asc())
                .fetch(row -> new RoundResult(
                        row.get(ROUND),
                        json.read(row.get(SURVIVORS), SURVIVOR_LIST),
                        json.read(row.get(SHORT_LIST), STRINGS)));
    }

    @Override
    @Transactional
    public void insertCandidates(UUID campaignId, List<Candidate> candidates) {
        chunks(candidates).forEach(chunk -> {
            var insert = dsl.insertInto(CANDIDATE)
                    .columns(CAMPAIGN_ID, CANDIDATE_ID, ROUND, POINT, OVERRIDES, DIGEST, REDRAWS);
            chunk.forEach(candidate -> insert.values(
                    campaignId,
                    candidate.id(),
                    candidate.round(),
                    json.write(candidate.point()),
                    json.write(plain(candidate)),
                    candidate.effectiveRulesDigest(),
                    candidate.redraws()));
            insert.onConflictDoNothing().execute();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID campaignId) {
        return dsl.selectFrom(CANDIDATE)
                .where(CAMPAIGN_ID.eq(campaignId))
                .orderBy(CANDIDATE_ID.asc())
                .fetch(row -> {
                    var overrides = new TreeMap<String, BigDecimal>();
                    json.read(row.get(OVERRIDES), DECIMALS)
                            .forEach((key, value) -> overrides.put(key, new BigDecimal(value)));
                    return new Candidate(
                            row.get(CANDIDATE_ID),
                            row.get(ROUND),
                            json.read(row.get(POINT), INTEGERS),
                            overrides,
                            row.get(DIGEST),
                            row.get(REDRAWS));
                });
    }

    @Override
    @Transactional
    public void insertWave(UUID campaignId, Wave wave) {
        dsl.insertInto(WAVE)
                .set(CAMPAIGN_ID, campaignId)
                .set(WAVE_INDEX, wave.index())
                .set(ROUND, wave.round())
                .set(EXPERIMENT, wave.experimentId())
                .set(RUN, wave.runId())
                .onConflictDoNothing()
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Wave> waves(UUID campaignId) {
        return dsl.selectFrom(WAVE)
                .where(CAMPAIGN_ID.eq(campaignId))
                .orderBy(WAVE_INDEX.asc())
                .fetch(row -> new Wave(row.get(WAVE_INDEX), row.get(ROUND), row.get(EXPERIMENT), row.get(RUN)));
    }

    @Override
    @Transactional
    public void insertGames(UUID campaignId, List<Game> games) {
        chunks(games).forEach(chunk -> {
            var insert = dsl.insertInto(GAME).columns(CAMPAIGN_ID, CANDIDATE_ID, SEED, WAVE_INDEX);
            chunk.forEach(game -> insert.values(campaignId, game.candidateId(), game.seed(), game.waveIndex()));
            insert.onConflictDoNothing().execute();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Game> games(UUID campaignId) {
        return dsl.selectFrom(GAME)
                .where(CAMPAIGN_ID.eq(campaignId))
                .orderBy(CANDIDATE_ID.asc(), SEED.asc())
                .fetch(row -> new Game(row.get(CANDIDATE_ID), row.get(SEED), row.get(WAVE_INDEX)));
    }

    @Override
    @Transactional
    public void appendLog(UUID campaignId, List<LogEntry> entries) {
        chunks(entries).forEach(chunk -> {
            var insert = dsl.insertInto(LOG).columns(CAMPAIGN_ID, SEQUENCE, ENTRY);
            chunk.forEach(entry -> insert.values(campaignId, entry.sequence(), entry.canonicalJson()));
            insert.onConflictDoNothing().execute();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LogEntry> log(UUID campaignId, int limit, int offset) {
        var total = dsl.fetchCount(LOG, CAMPAIGN_ID.eq(campaignId));
        var items = dsl.select(ENTRY)
                .from(LOG)
                .where(CAMPAIGN_ID.eq(campaignId))
                .orderBy(SEQUENCE.asc())
                .limit(limit)
                .offset(offset)
                .fetch(row -> entry(row.get(ENTRY)));
        return new Page<>(items, total);
    }

    private StoredCampaign stored(Record row) {
        var code = row.get(FAILURE_CODE);
        var campaign = new Campaign(
                row.get(CAMPAIGN_ID),
                json.read(row.get(DEFINITION), CampaignDefinition.class),
                row.get(PARALLELISM),
                CampaignState.valueOf(row.get(STATE)),
                row.get(SPENT),
                row.get(ROUND),
                json.read(row.get(SHORT_LIST), STRINGS),
                code == null ? null : new CampaignFailure(code, row.get(FAILURE_MESSAGE)),
                instant(row.get(CREATED)),
                instant(row.get(STARTED)),
                instant(row.get(FINISHED)));
        return new StoredCampaign(campaign, row.get(VERSION), instant(row.get(ACCRUED)));
    }

    private LogEntry entry(String text) {
        var node = json.read(text, JsonNode.class);
        var overrides = new TreeMap<String, BigDecimal>();
        node.path("overrides")
                .properties()
                .forEach(property -> overrides.put(
                        property.getKey(), new BigDecimal(property.getValue().asString())));
        var seeds = new ArrayList<String>();
        node.path("seeds").forEach(seed -> seeds.add(seed.asString()));
        var evaluation = node.path("evaluation");
        var evidence = node.path("evidence");
        return new LogEntry(
                node.path("sequence").asInt(),
                node.path("round").asInt(),
                node.path("candidateId").asString(),
                overrides,
                node.path("effectiveRulesDigest").asString(),
                seeds,
                node.path("gamesSpent").asInt(),
                node.path("redraws").asInt(),
                evaluation.isNull()
                        ? null
                        : new LogEntry.Evaluation(
                                evaluation.path("verdict").asString(),
                                evaluation.path("score").isNull()
                                        ? null
                                        : evaluation.path("score").asDouble()),
                LogEntry.Decision.valueOf(node.path("decision").asString()),
                node.path("reason").isNull()
                        ? null
                        : LogEntry.Reason.valueOf(node.path("reason").asString()),
                evidence.isNull() ? null : evidence(evidence));
    }

    private static LogEntry.Evidence evidence(JsonNode node) {
        List<String> lineIds = null;
        if (node.has("lineIds")) {
            lineIds = new ArrayList<>();
            for (var id : node.path("lineIds")) {
                lineIds.add(id.asString());
            }
        }
        return new LogEntry.Evidence(
                lineIds, node.has("dominatedBy") ? node.path("dominatedBy").asString() : null);
    }

    private static TreeMap<String, String> plain(Candidate candidate) {
        var plain = new TreeMap<String, String>();
        candidate
                .overrides()
                .forEach((key, value) ->
                        plain.put(key, value.stripTrailingZeros().toPlainString()));
        return plain;
    }

    private static <T> List<List<T>> chunks(List<T> rows) {
        var chunks = new ArrayList<List<T>>();
        for (var from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            chunks.add(rows.subList(from, Math.min(rows.size(), from + ROWS_PER_STATEMENT)));
        }
        return chunks;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
