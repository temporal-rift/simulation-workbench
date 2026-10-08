package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** Maps result sets and JSON columns of the execution tables to domain values. */
final class Rows {

    private static final String STATE = "state";
    private static final TypeReference<List<SeatPlan>> SEATS = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    Rows(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    Run run(ResultSet rs) throws SQLException {
        return Run.restore(
                rs.getObject("run_id", UUID.class),
                rs.getObject("experiment_id", UUID.class),
                RunState.valueOf(rs.getString(STATE)),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                failure(rs));
    }

    LogicalCase logicalCase(ResultSet rs) throws SQLException {
        var resultJson = rs.getString("result_json");
        return new LogicalCase(
                rs.getObject("case_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("case_key", UUID.class),
                rs.getInt("ordinal"),
                rs.getString("variant_label"),
                rs.getString("seed"),
                rs.getInt("player_count"),
                read(rs.getString("seats_json"), SEATS),
                CaseState.valueOf(rs.getString(STATE)),
                resultJson == null ? null : read(resultJson, CaseResult.class));
    }

    Attempt attempt(ResultSet rs) throws SQLException {
        return new Attempt(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getInt("ordinal"),
                AttemptState.valueOf(rs.getString(STATE)),
                rs.getObject("game_id", UUID.class),
                rs.getString("lane_id"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                failure(rs));
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored " + type.getSimpleName() + " is not valid JSON", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored JSON column is not valid", e);
        }
    }

    private static Failure failure(ResultSet rs) throws SQLException {
        var code = rs.getString("failure_code");
        return code == null ? null : new Failure(FailureCode.valueOf(code), rs.getString("failure_message"));
    }
}
