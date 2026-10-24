package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import java.util.List;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.Table;

/** The one-page-of-identifiers-plus-count shape the optional-filter lists share. */
public final class JooqIdPages {

    private JooqIdPages() {}

    /** The identifiers of the page of {@code limit} rows after {@code offset} matching the condition, in order. */
    public static <T> List<T> ids(
            DSLContext dsl,
            Table<?> table,
            Field<T> id,
            Condition where,
            List<? extends OrderField<?>> order,
            int limit,
            int offset) {
        return dsl.select(id)
                .from(table)
                .where(where)
                .orderBy(order)
                .limit(limit)
                .offset(offset)
                .fetch(id);
    }

    /** How many rows match the condition, whatever the page. */
    public static long count(DSLContext dsl, Table<?> table, Condition where) {
        return dsl.fetchCount(table, where);
    }
}
