package io.github.temporalrift.workbench.search.domain.log;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.shared.text.JsonText;

/**
 * One decision of the search about one candidate, with everything needed to understand it. The log is the campaign's
 * evidence and must be byte-identical for the same definition, so an entry holds nothing that depends on wall time,
 * the host or the order games happened to finish in.
 *
 * @param sequence the decision order within the campaign, from 1
 * @param overrides what the candidate changes from the baseline
 * @param seeds the seeds the candidate has played so far, in the order of the seed sequence
 * @param gamesSpent games the candidate has played so far
 * @param redraws draws thrown away before the candidate was generated; meaningful on {@link Decision#GENERATED}
 * @param evaluation null when the candidate was not evaluated
 * @param reason null unless the candidate was discarded
 * @param evidence null unless the reason needs it
 */
public record LogEntry(
        int sequence,
        int round,
        String candidateId,
        SortedMap<String, BigDecimal> overrides,
        String effectiveRulesDigest,
        List<String> seeds,
        int gamesSpent,
        int redraws,
        Evaluation evaluation,
        Decision decision,
        Reason reason,
        Evidence evidence) {

    public LogEntry {
        seeds = List.copyOf(seeds);
    }

    /** What was decided. */
    public enum Decision {
        GENERATED,
        KEPT,
        DISCARDED,
        SHORT_LISTED
    }

    /** Why a candidate was discarded. */
    public enum Reason {
        GATE_FAILS,
        DOMINATED,
        NOT_IN_TOP_FRACTION,
        /** The plan ended and the candidate, though kept, still fails a gate, so it is not short-listed. */
        GATES_NOT_MET,
        /** Only on version 1 campaigns, whose search ended on a time budget. */
        BUDGET_EXHAUSTED
    }

    /**
     * The compact screening evaluation behind a decision.
     *
     * @param score null for an invalid evaluation
     * @param gateMiss how far the candidate misses the gates; null for an invalid evaluation and on version 1 entries
     */
    public record Evaluation(String verdict, Double score, Double gateMiss) {

        /** An evaluation recorded without a gate miss, as version 1 records them. */
        public Evaluation(String verdict, Double score) {
            this(verdict, score, null);
        }
    }

    /**
     * Why a decision was taken.
     *
     * @param lineIds the failing gate lines, for {@code GATE_FAILS}
     * @param dominatedBy the dominating candidate, for {@code DOMINATED}
     */
    public record Evidence(List<String> lineIds, String dominatedBy) {

        public Evidence {
            lineIds = lineIds == null ? null : List.copyOf(lineIds);
        }

        public static Evidence failingLines(List<String> lineIds) {
            return new Evidence(lineIds, null);
        }

        public static Evidence dominatedBy(String candidateId) {
            return new Evidence(null, candidateId);
        }
    }

    public LogEntry numbered(int next) {
        return new LogEntry(
                next,
                round,
                candidateId,
                overrides,
                effectiveRulesDigest,
                seeds,
                gamesSpent,
                redraws,
                evaluation,
                decision,
                reason,
                evidence);
    }

    /**
     * The entry as canonical JSON: fixed key order, decimals in plain form, no whitespace. Two campaigns that made the
     * same decisions produce the same text.
     */
    public String canonicalJson() {
        var factory = JsonNodeFactory.instance;
        var node = factory.objectNode();
        node.put("sequence", sequence);
        node.put("round", round);
        node.put("candidateId", candidateId);
        var overridesNode = node.putObject("overrides");
        overrides.forEach((key, value) ->
                overridesNode.put(key, value.stripTrailingZeros().toPlainString()));
        node.put("effectiveRulesDigest", effectiveRulesDigest);
        var seedsNode = node.putArray("seeds");
        seeds.forEach(seedsNode::add);
        node.put("gamesSpent", gamesSpent);
        node.put("redraws", redraws);
        putEvaluation(node);
        node.put("decision", decision.name());
        node.put("reason", reason == null ? null : reason.name());
        putEvidence(node);
        return JsonText.write(node, "a campaign log entry");
    }

    private void putEvaluation(ObjectNode node) {
        if (evaluation == null) {
            node.putNull("evaluation");
            return;
        }
        var evaluationNode = node.putObject("evaluation");
        evaluationNode.put("verdict", evaluation.verdict());
        if (evaluation.score() == null) {
            evaluationNode.putNull("score");
        } else {
            evaluationNode.put("score", evaluation.score());
        }
        // Version 1 entries record no gate miss, so their canonical form stays what it always was.
        if (evaluation.gateMiss() != null) {
            evaluationNode.put("gateMiss", evaluation.gateMiss());
        }
    }

    private void putEvidence(ObjectNode node) {
        if (evidence == null) {
            node.putNull("evidence");
            return;
        }
        var evidenceNode = node.putObject("evidence");
        if (evidence.lineIds() != null) {
            var lineIds = evidenceNode.putArray("lineIds");
            evidence.lineIds().forEach(lineIds::add);
        }
        if (evidence.dominatedBy() != null) {
            evidenceNode.put("dominatedBy", evidence.dominatedBy());
        }
    }
}
