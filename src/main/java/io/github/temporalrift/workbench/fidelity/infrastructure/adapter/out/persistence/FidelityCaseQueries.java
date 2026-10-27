package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

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

/** The optional-filter list of a check's cases, which Spring Data cannot express: it selects one page of row ids. */
@Component
class FidelityCaseQueries {

    private static final String TABLE = "fidelity_case";
    private static final Table<?> CASE = DSL.table(DSL.name(TABLE));
    private static final Field<UUID> ROW_ID = DSL.field(DSL.name(TABLE, "row_id"), SQLDataType.UUID);
    private static final Field<UUID> CHECK_ID = DSL.field(DSL.name(TABLE, "check_id"), SQLDataType.UUID);
    private static final Field<String> VARIANT = DSL.field(DSL.name(TABLE, "variant_label"), SQLDataType.VARCHAR);
    private static final Field<String> RESULT = DSL.field(DSL.name(TABLE, "result"), SQLDataType.VARCHAR);
    private static final Field<Integer> ORDINAL = DSL.field(DSL.name(TABLE, "ordinal"), SQLDataType.INTEGER);

    private final DSLContext dsl;

    FidelityCaseQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    List<UUID> rowIds(UUID checkId, String variantLabel, String result, int limit, int offset) {
        return JooqIdPages.ids(
                dsl, CASE, ROW_ID, filter(checkId, variantLabel, result), List.of(ORDINAL), limit, offset);
    }

    long count(UUID checkId, String variantLabel, String result) {
        return JooqIdPages.count(dsl, CASE, filter(checkId, variantLabel, result));
    }

    private static Condition filter(UUID checkId, String variantLabel, String result) {
        return CHECK_ID.eq(checkId).and(optional(VARIANT, variantLabel)).and(optional(RESULT, result));
    }

    private static Condition optional(Field<String> field, String value) {
        return value == null ? DSL.trueCondition() : field.eq(value);
    }
}
