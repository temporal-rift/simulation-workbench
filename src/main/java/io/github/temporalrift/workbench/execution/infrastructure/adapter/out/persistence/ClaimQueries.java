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

import io.github.temporalrift.workbench.execution.domain.run.LaneFit;

/**
 * The claim selections that match a pending job against the fits of the free lanes, which Spring Data cannot express
 * as row-value comparisons; the claimed rows are then read through the repositories.
 */
@Component
class ClaimQueries {

    private static final String CASE_ID_COLUMN = "case_id";
    private static final String STATE_COLUMN = "state";
    private static final String CASE_TABLE = "run_case";
    private static final String REPRODUCTION_TABLE = "reproduction";

    private static final Table<?> CASE = DSL.table(DSL.name(CASE_TABLE));
    private static final Field<UUID> CASE_ID = DSL.field(DSL.name(CASE_TABLE, CASE_ID_COLUMN), SQLDataType.UUID);
    private static final Field<UUID> CASE_RUN = DSL.field(DSL.name(CASE_TABLE, "run_id"), SQLDataType.UUID);
    private static final Field<String> CASE_STATE = DSL.field(DSL.name(CASE_TABLE, STATE_COLUMN), SQLDataType.VARCHAR);
    private static final Field<Integer> CASE_ORDINAL = DSL.field(DSL.name(CASE_TABLE, "ordinal"), SQLDataType.INTEGER);
    private static final Field<String> CASE_FIT = DSL.field(DSL.name(CASE_TABLE, "lane_fit"), SQLDataType.VARCHAR);

    private static final Table<?> REPRODUCTION = DSL.table(DSL.name(REPRODUCTION_TABLE));
    private static final Field<UUID> REPRODUCTION_ID =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "reproduction_id"), SQLDataType.UUID);
    private static final Field<String> REPRODUCTION_LANE =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "lane_id"), SQLDataType.VARCHAR);
    private static final Field<String> REPRODUCTION_FIT =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "lane_fit"), SQLDataType.VARCHAR);
    private static final Field<String> REPRODUCTION_STATE =
            DSL.field(DSL.name(REPRODUCTION_TABLE, STATE_COLUMN), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> REPRODUCTION_CREATED =
            DSL.field(DSL.name(REPRODUCTION_TABLE, "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    ClaimQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The first case of the run in matrix order in {@code state} whose fit is among {@code fits}.
     */
    Optional<UUID> firstCaseServedBy(UUID runId, String state, Collection<LaneFit> fits) {
        if (fits.isEmpty()) {
            return Optional.empty();
        }
        return dsl.select(CASE_ID)
                .from(CASE)
                .where(CASE_RUN.eq(runId))
                .and(CASE_STATE.eq(state))
                .and(CASE_FIT.in(fits.stream().map(LaneFit::digest).toList()))
                .orderBy(CASE_ORDINAL)
                .limit(1)
                .fetchOptional(CASE_ID);
    }

    /**
     * The oldest reproduction in {@code state} whose lane is free and still has the fit the reproduction needs, locked
     * for this transaction; rows another worker holds are skipped.
     */
    Optional<UUID> lockOldestReproductionServedBy(String state, Map<String, LaneFit> freeLanes) {
        if (freeLanes.isEmpty()) {
            return Optional.empty();
        }
        var served = freeLanes.entrySet().stream()
                .map(lane -> DSL.row(lane.getKey(), lane.getValue().digest()))
                .toList();
        return dsl.select(REPRODUCTION_ID)
                .from(REPRODUCTION)
                .where(REPRODUCTION_STATE.eq(state))
                .and(DSL.row(REPRODUCTION_LANE, REPRODUCTION_FIT).in(served))
                .orderBy(REPRODUCTION_CREATED)
                .limit(1)
                .forUpdate()
                .of(REPRODUCTION)
                .skipLocked()
                .fetchOptional(REPRODUCTION_ID);
    }
}
