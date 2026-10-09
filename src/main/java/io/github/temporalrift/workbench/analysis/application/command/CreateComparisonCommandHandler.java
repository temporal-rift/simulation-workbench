package io.github.temporalrift.workbench.analysis.application.command;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.application.port.in.CreateComparisonUseCase;
import io.github.temporalrift.workbench.analysis.application.query.ComparisonEvaluator;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonIdempotencyConflictException;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;
import io.github.temporalrift.workbench.analysis.domain.comparison.ExperimentDefinition;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

/**
 * Defines and stores a comparison of two run variants. The same key with the same request returns the original
 * comparison; the same key with another request conflicts.
 */
public class CreateComparisonCommandHandler implements CreateComparisonUseCase {

    private final RunSource runs;
    private final ExperimentDefinitions experiments;
    private final ComparisonRepository comparisons;
    private final ComparisonEvaluator evaluator;

    public CreateComparisonCommandHandler(
            RunSource runs,
            ExperimentDefinitions experiments,
            ComparisonRepository comparisons,
            ComparisonEvaluator evaluator) {
        this.runs = runs;
        this.experiments = experiments;
        this.comparisons = comparisons;
        this.evaluator = evaluator;
    }

    @Override
    public ComparisonResult handle(Command command) {
        var requestHash = hash(command.baseline(), command.candidate());
        var claimed = comparisons.findByKey(command.idempotencyKey());
        if (claimed.isPresent()) {
            return evaluator.evaluate(replayed(claimed.get(), requestHash));
        }
        var definition = ComparisonDefinition.define(
                UUID.randomUUID(),
                command.baseline(),
                definitionOf(command.baseline()),
                command.candidate(),
                definitionOf(command.candidate()),
                AnalysisVersion.CURRENT);
        if (!comparisons.create(command.idempotencyKey(), requestHash, definition)) {
            definition =
                    replayed(comparisons.findByKey(command.idempotencyKey()).orElseThrow(), requestHash);
        }
        return evaluator.evaluate(definition);
    }

    private ExperimentDefinition definitionOf(ComparisonSide side) {
        var experimentId = runs.experimentOf(side.runId())
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Run", side.runId()));
        return experiments
                .find(experimentId)
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Experiment", experimentId))
                .definition();
    }

    private static ComparisonDefinition replayed(ComparisonRepository.Claim claim, String requestHash) {
        if (!claim.requestHash().equals(requestHash)) {
            throw new ComparisonIdempotencyConflictException();
        }
        return claim.definition();
    }

    private static String hash(ComparisonSide baseline, ComparisonSide candidate) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            for (var part : new String[] {
                "createComparison",
                baseline.runId().toString(),
                baseline.variantLabel(),
                candidate.runId().toString(),
                candidate.variantLabel()
            }) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                sha256.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha256.update(bytes);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
