package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * The optional-filter lists, which Spring Data cannot express: they only select the identifiers of one page and
 * count the matches, and the rows themselves are then read through the repositories.
 */
@Component
class RunListingQueries {

    private static final Table<?> RUN = DSL.table(DSL.name("run"));
    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("run", "run_id"), SQLDataType.UUID);
    private static final Field<UUID> RUN_EXPERIMENT = DSL.field(DSL.name("run", "experiment_id"), SQLDataType.UUID);
    private static final Field<String> RUN_STATE = DSL.field(DSL.name("run", "state"), SQLDataType.VARCHAR);
    private static final Field<java.time.OffsetDateTime> RUN_CREATED =
            DSL.field(DSL.name("run", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private static final Table<?> CASE = DSL.table(DSL.name("run_case"));
    private static final Field<UUID> CASE_ID = DSL.field(DSL.name("run_case", "case_id"), SQLDataType.UUID);
    private static final Field<UUID> CASE_RUN = DSL.field(DSL.name("run_case", "run_id"), SQLDataType.UUID);
    private static final Field<String> CASE_STATE = DSL.field(DSL.name("run_case", "state"), SQLDataType.VARCHAR);
    private static final Field<String> CASE_VARIANT =
            DSL.field(DSL.name("run_case", "variant_label"), SQLDataType.VARCHAR);
    private static final Field<Integer> CASE_ORDINAL = DSL.field(DSL.name("run_case", "ordinal"), SQLDataType.INTEGER);

    private final DSLContext dsl;

    RunListingQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The identifiers of one page of runs, newest first. */
    List<UUID> runIds(UUID experimentId, String state, int limit, int offset) {
        return dsl.select(RUN_ID)
                .from(RUN)
                .where(runFilter(experimentId, state))
                .orderBy(RUN_CREATED.desc(), RUN_ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(RUN_ID);
    }

    long runCount(UUID experimentId, String state) {
        return dsl.fetchCount(RUN, runFilter(experimentId, state));
    }

    /** The identifiers of one page of a run's cases, in matrix order. */
    List<UUID> caseIds(UUID runId, String state, String variantLabel, int limit, int offset) {
        return dsl.select(CASE_ID)
                .from(CASE)
                .where(caseFilter(runId, state, variantLabel))
                .orderBy(CASE_ORDINAL)
                .limit(limit)
                .offset(offset)
                .fetch(CASE_ID);
    }

    long caseCount(UUID runId, String state, String variantLabel) {
        return dsl.fetchCount(CASE, caseFilter(runId, state, variantLabel));
    }

    private static Condition runFilter(UUID experimentId, String state) {
        return optional(RUN_EXPERIMENT, experimentId).and(optional(RUN_STATE, state));
    }

    private static Condition caseFilter(UUID runId, String state, String variantLabel) {
        return CASE_RUN.eq(runId).and(optional(CASE_STATE, state)).and(optional(CASE_VARIANT, variantLabel));
    }

    private static <T> Condition optional(Field<T> field, T value) {
        return value == null ? DSL.noCondition() : field.eq(value);
    }
}
