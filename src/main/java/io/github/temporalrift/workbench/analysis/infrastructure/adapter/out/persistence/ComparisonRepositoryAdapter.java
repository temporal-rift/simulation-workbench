package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;
import io.github.temporalrift.workbench.analysis.domain.comparison.DeclaredDifference;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** Stored comparison definitions; a unique idempotency key makes a concurrent repeat lose the insert. */
@Component
public class ComparisonRepositoryAdapter implements ComparisonRepository {

    private static final TypeReference<List<DeclaredDifference>> DIFFERENCES = new TypeReference<>() {};

    private final AnalysisComparisonJpaRepository comparisons;
    private final ComparisonListingQueries listing;
    private final InsertOnce insertOnce;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ComparisonRepositoryAdapter(
            AnalysisComparisonJpaRepository comparisons,
            ComparisonListingQueries listing,
            InsertOnce insertOnce,
            ObjectMapper objectMapper,
            Clock clock) {
        this.comparisons = comparisons;
        this.listing = listing;
        this.insertOnce = insertOnce;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Optional<ComparisonDefinition> find(UUID comparisonId) {
        return comparisons.findById(comparisonId).map(this::definition);
    }

    @Override
    public List<Stored> list(UUID runId, int limit, int offset) {
        var ids = listing.ids(runId, limit, offset);
        var byId = comparisons.findAllById(ids).stream()
                .collect(Collectors.toMap(AnalysisComparisonJpaEntity::comparisonId, Function.identity()));
        return ids.stream()
                .map(byId::get)
                .map(entity -> new Stored(definition(entity), entity.createdAt()))
                .toList();
    }

    @Override
    public long count(UUID runId) {
        return listing.count(runId);
    }

    @Override
    public Optional<Claim> findByKey(UUID idempotencyKey) {
        return comparisons
                .findByIdempotencyKey(idempotencyKey)
                .map(entity -> new Claim(entity.requestHash(), definition(entity)));
    }

    @Override
    public boolean create(UUID idempotencyKey, String requestHash, ComparisonDefinition definition) {
        return insertOnce.insert(() -> comparisons.saveAndFlush(new AnalysisComparisonJpaEntity(
                definition, idempotencyKey, requestHash, write(definition.declaredDifferences()), clock.instant())));
    }

    private ComparisonDefinition definition(AnalysisComparisonJpaEntity entity) {
        return new ComparisonDefinition(
                entity.comparisonId(),
                new ComparisonSide(entity.baselineRunId(), entity.baselineVariant()),
                new ComparisonSide(entity.candidateRunId(), entity.candidateVariant()),
                read(entity.declaredDifferences()),
                new AnalysisVersion(entity.analysisVersion(), Long.parseUnsignedLong(entity.analysisSeed())));
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
