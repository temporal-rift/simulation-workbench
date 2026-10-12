package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
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
 * The claim selections that match a pending job against the bundles of the free lanes, which Spring Data cannot
 * express as row-value comparisons; the claimed rows are then read through the repositories.
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

    /** The first case of the run in matrix order in {@code state} whose bundle is among {@code bundles}. */
    Optional<UUID> firstCaseServedBy(UUID runId, String state, Collection<BundleDigests> bundles) {
        if (bundles.isEmpty()) {
            return Optional.empty();
        }
        var served = bundles.stream()
                .map(bundle -> DSL.row(bundle.rulesDigest(), bundle.contentDigest()))
                .toList();
        return dsl.select(CASE_ID)
                .from(CASE)
                .where(CASE_RUN.eq(runId))
                .and(CASE_STATE.eq(state))
                .and(DSL.row(CASE_RULES, CASE_CONTENT).in(served))
                .orderBy(CASE_ORDINAL)
                .limit(1)
                .fetchOptional(CASE_ID);
    }

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
