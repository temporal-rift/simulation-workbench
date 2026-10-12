package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * The claim selections that match a pending job against the bundles of the free lanes, and the most recent attempt of
 * each lane, which Spring Data cannot express as row-value comparisons or per-lane selections; the claimed rows are
 * then read through the repositories.
 */
@Component
class ClaimQueries {

    private static final String CASE_TABLE = "run_case";
    private static final String REPRODUCTION_TABLE = "reproduction";

    private static final Table<?> CASE = DSL.table(DSL.name(CASE_TABLE));
    private static final Field<UUID> CASE_ID = DSL.field(DSL.name(CASE_TABLE, "case_id"), SQLDataType.UUID);
    private static final Field<UUID> CASE_RUN = DSL.field(DSL.name(CASE_TABLE, "run_id"), SQLDataType.UUID);
    private static final Field<String> CASE_STATE = DSL.field(DSL.name(CASE_TABLE, "state"), SQLDataType.VARCHAR);
    private static final Field<Integer> CASE_ORDINAL = DSL.field(DSL.name(CASE_TABLE, "ordinal"), SQLDataType.INTEGER);
    private static final Field<String> CASE_RULES =
            DSL.field(DSL.name(CASE_TABLE, "rules_digest"), SQLDataType.VARCHAR);
    private static final Field<String> CASE_CONTENT =
            DSL.field(DSL.name(CASE_TABLE, "content_digest"), SQLDataType.VARCHAR);

    private static final Field<UUID> CASE_KEY = DSL.field(DSL.name(CASE_TABLE, "case_key"), SQLDataType.UUID);

    private static final String ATTEMPT_TABLE = "case_attempt";
    private static final Table<?> ATTEMPT = DSL.table(DSL.name(ATTEMPT_TABLE));
    private static final Field<UUID> ATTEMPT_CASE = DSL.field(DSL.name(ATTEMPT_TABLE, "case_id"), SQLDataType.UUID);
    private static final Field<String> ATTEMPT_LANE =
            DSL.field(DSL.name(ATTEMPT_TABLE, "lane_id"), SQLDataType.VARCHAR);
    private static final Field<String> ATTEMPT_STATE = DSL.field(DSL.name(ATTEMPT_TABLE, "state"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> ATTEMPT_STARTED =
            DSL.field(DSL.name(ATTEMPT_TABLE, "started_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private static final Table<?> REPRODUCTION = DSL.table(DSL.name(REPRODUCTION_TABLE));
    private static final Field<UUID> REPRODUCTION_ID =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "reproduction_id"), SQLDataType.UUID);
    private static final Field<UUID> REPRODUCTION_CASE =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "case_id"), SQLDataType.UUID);
    private static final Field<String> REPRODUCTION_LANE =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "lane_id"), SQLDataType.VARCHAR);
    private static final Field<String> REPRODUCTION_STATE =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "state"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> REPRODUCTION_CREATED =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    ClaimQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The first case of the run in matrix order in {@code state} whose bundle is among {@code bundles} or whose id is
     * among {@code caseIds}.
     */
    Optional<UUID> firstCaseServedBy(
            UUID runId, String state, Collection<BundleDigests> bundles, Collection<UUID> caseIds) {
        if (bundles.isEmpty() && caseIds.isEmpty()) {
            return Optional.empty();
        }
        var served = bundles.stream()
                .map(bundle -> DSL.row(bundle.rulesDigest(), bundle.contentDigest()))
                .toList();
        var offered = served.isEmpty()
                ? DSL.falseCondition()
                : DSL.row(CASE_RULES, CASE_CONTENT).in(served);
        return dsl.select(CASE_ID)
                .from(CASE)
                .where(CASE_RUN.eq(runId))
                .and(CASE_STATE.eq(state))
                .and(offered.or(caseIds.isEmpty() ? DSL.falseCondition() : CASE_ID.in(caseIds)))
                .orderBy(CASE_ORDINAL)
                .limit(1)
                .fetchOptional(CASE_ID);
    }

    /** The most recent attempt that played on each lane, with its case's state and key. */
    List<LaneAttempt> lastAttemptPerLane() {
        return dsl.select(ATTEMPT_LANE, ATTEMPT_STATE, ATTEMPT_CASE, CASE_KEY, CASE_STATE)
                .distinctOn(ATTEMPT_LANE)
                .from(ATTEMPT)
                .join(CASE)
                .on(CASE_ID.eq(ATTEMPT_CASE))
                .where(ATTEMPT_LANE.isNotNull())
                .orderBy(ATTEMPT_LANE, ATTEMPT_STARTED.desc())
                .fetch(row -> new LaneAttempt(
                        row.get(ATTEMPT_LANE),
                        row.get(ATTEMPT_STATE),
                        row.get(ATTEMPT_CASE),
                        row.get(CASE_KEY),
                        row.get(CASE_STATE)));
    }

    record LaneAttempt(String laneId, String attemptState, UUID caseId, UUID caseKey, String caseState) {}

    /**
     * The oldest reproduction in {@code state} whose lane is free and still serves its case's bundle, locked for this
     * transaction; rows another worker holds are skipped.
     */
    Optional<UUID> lockOldestReproductionServedBy(String state, Map<String, BundleDigests> freeLanes) {
        if (freeLanes.isEmpty()) {
            return Optional.empty();
        }
        var served = freeLanes.entrySet().stream()
                .map(lane -> DSL.row(
                        lane.getKey(),
                        lane.getValue().rulesDigest(),
                        lane.getValue().contentDigest()))
                .toList();
        return dsl.select(REPRODUCTION_ID)
                .from(REPRODUCTION)
                .join(CASE)
                .on(CASE_ID.eq(REPRODUCTION_CASE))
                .where(REPRODUCTION_STATE.eq(state))
                .and(DSL.row(REPRODUCTION_LANE, CASE_RULES, CASE_CONTENT).in(served))
                .orderBy(REPRODUCTION_CREATED)
                .limit(1)
                .forUpdate()
                .of(REPRODUCTION)
                .skipLocked()
                .fetchOptional(REPRODUCTION_ID);
    }
}
