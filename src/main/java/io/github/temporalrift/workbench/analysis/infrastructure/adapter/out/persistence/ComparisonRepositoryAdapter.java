package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;
import io.github.temporalrift.workbench.analysis.domain.comparison.DeclaredDifference;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;

/** PostgreSQL comparison definitions; a unique idempotency key makes a concurrent repeat lose the insert. */
public class ComparisonRepositoryAdapter implements ComparisonRepository {

    private static final String COLUMNS = "comparison_id, request_hash, baseline_run_id, baseline_variant,"
            + " candidate_run_id, candidate_variant, declared_differences, analysis_version, analysis_seed, created_at";
    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM analysis_comparison";
    private static final String RUN_FILTER =
            " WHERE (CAST(:run AS uuid) IS NULL OR baseline_run_id = :run OR candidate_run_id = :run)";
    private static final String LIST =
            SELECT_ALL + RUN_FILTER + " ORDER BY created_at DESC, comparison_id DESC LIMIT :limit OFFSET :offset";
    private static final String COUNT = "SELECT count(*) FROM analysis_comparison" + RUN_FILTER;
    private static final TypeReference<List<DeclaredDifference>> DIFFERENCES = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ComparisonRepositoryAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Optional<ComparisonDefinition> find(UUID comparisonId) {
        return jdbc
                .query(
                        SELECT_ALL + " WHERE comparison_id = :id",
                        new MapSqlParameterSource("id", comparisonId),
                        (rs, i) -> definition(rs))
                .stream()
                .findFirst();
    }

    @Override
    public List<Stored> list(UUID runId, int limit, int offset) {
        return jdbc.query(
                LIST,
                new MapSqlParameterSource("run", runId).addValue("limit", limit).addValue("offset", offset),
                (rs, i) -> new Stored(
                        definition(rs),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    @Override
    public long count(UUID runId) {
        var total = jdbc.queryForObject(COUNT, new MapSqlParameterSource("run", runId), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<Claim> findByKey(UUID idempotencyKey) {
        return jdbc
                .query(
                        SELECT_ALL + " WHERE idempotency_key = :key",
                        new MapSqlParameterSource("key", idempotencyKey),
                        (rs, i) -> new Claim(rs.getString("request_hash"), definition(rs)))
                .stream()
                .findFirst();
    }

    @Override
    public boolean create(UUID idempotencyKey, String requestHash, ComparisonDefinition definition) {
        return jdbc.update(
                        "INSERT INTO analysis_comparison (comparison_id, idempotency_key, request_hash,"
                                + " baseline_run_id, baseline_variant, candidate_run_id, candidate_variant,"
                                + " declared_differences, analysis_version, analysis_seed, created_at)"
                                + " VALUES (:id, :key, :hash, :baselineRun, :baselineVariant, :candidateRun,"
                                + " :candidateVariant, :differences, :version, :seed, :at)"
                                + " ON CONFLICT (idempotency_key) DO NOTHING",
                        new MapSqlParameterSource()
                                .addValue("id", definition.comparisonId())
                                .addValue("key", idempotencyKey)
                                .addValue("hash", requestHash)
                                .addValue("baselineRun", definition.baseline().runId())
                                .addValue(
                                        "baselineVariant", definition.baseline().variantLabel())
                                .addValue("candidateRun", definition.candidate().runId())
                                .addValue(
                                        "candidateVariant",
                                        definition.candidate().variantLabel())
                                .addValue("differences", write(definition.declaredDifferences()))
                                .addValue("version", definition.analysis().version())
                                .addValue("seed", definition.analysis().seedText())
                                .addValue("at", Timestamp.from(clock.instant())))
                == 1;
    }

    private ComparisonDefinition definition(ResultSet rs) throws SQLException {
        return new ComparisonDefinition(
                rs.getObject("comparison_id", UUID.class),
                new ComparisonSide(rs.getObject("baseline_run_id", UUID.class), rs.getString("baseline_variant")),
                new ComparisonSide(rs.getObject("candidate_run_id", UUID.class), rs.getString("candidate_variant")),
                read(rs.getString("declared_differences")),
                new AnalysisVersion(
                        rs.getString("analysis_version"), Long.parseUnsignedLong(rs.getString("analysis_seed"))));
    }

    private String write(List<DeclaredDifference> differences) {
        try {
            return objectMapper.writeValueAsString(differences);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot store declared differences", e);
        }
    }

    private List<DeclaredDifference> read(String json) {
        try {
            return objectMapper.readValue(json, DIFFERENCES);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored declared differences are unreadable", e);
        }
    }
}
