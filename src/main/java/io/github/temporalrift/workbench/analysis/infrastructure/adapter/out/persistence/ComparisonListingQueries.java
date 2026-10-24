package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.JooqIdPages;

/**
 * The optional-filter list, which Spring Data cannot express: it only selects the identifiers of one page and
 * counts the matches, and the rows themselves are then read through the repository.
 */
@Component
class ComparisonListingQueries {

    private static final String TABLE = "analysis_comparison";
    private static final Table<?> COMPARISON = DSL.table(DSL.name(TABLE));
    private static final Field<UUID> ID = DSL.field(DSL.name(TABLE, "comparison_id"), SQLDataType.UUID);
    private static final Field<UUID> BASELINE = DSL.field(DSL.name(TABLE, "baseline_run_id"), SQLDataType.UUID);
    private static final Field<UUID> CANDIDATE = DSL.field(DSL.name(TABLE, "candidate_run_id"), SQLDataType.UUID);
    private static final Field<java.time.OffsetDateTime> CREATED =
            DSL.field(DSL.name(TABLE, "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    ComparisonListingQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The identifiers of one page of comparisons, newest first, optionally those with a side in the run. */
    List<UUID> ids(UUID runId, int limit, int offset) {
        return JooqIdPages.ids(dsl, COMPARISON, ID, sideIn(runId), List.of(CREATED.desc(), ID.desc()), limit, offset);
    }

    long count(UUID runId) {
        return JooqIdPages.count(dsl, COMPARISON, sideIn(runId));
    }

    private static Condition sideIn(UUID runId) {
        return runId == null ? DSL.noCondition() : BASELINE.eq(runId).or(CANDIDATE.eq(runId));
    }
}
