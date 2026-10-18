package io.github.temporalrift.workbench.execution.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/** Freezes manifests, starts their runs and plays them with one worker per slot, as the worker threads do. */
final class SimulatorPlay {

    private final CreateExperimentUseCase createExperiment;
    private final StartRunUseCase startRun;
    private final RunBatchUseCase batch;
    private final JdbcTemplate jdbc;

    SimulatorPlay(
            CreateExperimentUseCase createExperiment,
            StartRunUseCase startRun,
            RunBatchUseCase batch,
            JdbcTemplate jdbc) {
        this.createExperiment = createExperiment;
        this.startRun = startRun;
        this.batch = batch;
        this.jdbc = jdbc;
    }

    /** Empties the store and prepares the slots, which start unprepared until the first recovery resets them. */
    void clean() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM evidence_event");
        jdbc.update("DELETE FROM evidence_step");
        jdbc.update("DELETE FROM evidence_source_offset");
        jdbc.update("DELETE FROM case_evidence");
        jdbc.update("DELETE FROM evidence_artifact");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        batch.recoverAfterRestart();
    }

    UUID start(JsonNode manifest) {
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
        return startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
    }

    /** Plays every case of the run with {@code workers} threads at once, waiting for slots still resetting. */
    void drain(UUID runId, int workers) {
        batch.maintain();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var done = IntStream.range(0, workers)
                    .mapToObj(worker -> pool.submit(() -> play(runId, "simulator-worker-" + worker)))
                    .toList();
            for (var future : done) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (ExecutionException | TimeoutException e) {
                    throw new IllegalStateException("A simulator worker did not finish", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while a simulator worker played", e);
                }
            }
        }
        batch.maintain();
    }

    /** Claims cases until none is pending, waiting for slots that are still resetting. */
    private Void play(UUID runId, String worker) {
        Awaitility.await()
                .atMost(Duration.ofMinutes(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    while (batch.runNextCase(worker)) {
                        // keep claiming until nothing is pending
                    }
                    return isPlayed(runId);
                });
        return null;
    }

    private boolean isPlayed(UUID runId) {
        var states = states(runId).values();
        return !states.contains("PENDING") && !states.contains("RUNNING");
    }

    Map<UUID, String> states(UUID runId) {
        return jdbc.queryForList("SELECT case_key, state FROM run_case WHERE run_id = ?", runId).stream()
                .collect(Collectors.toMap(row -> (UUID) row.get("case_key"), row -> (String) row.get("state")));
    }

    Map<UUID, String> digestsByCaseKey(UUID runId) {
        return jdbc
                .queryForList(
                        "SELECT case_key, result_json FROM run_case WHERE run_id = ? AND state = 'SUCCEEDED'", runId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row.get("case_key"), row -> digestOf((String) row.get("result_json"))));
    }

    /** Where the games of one case first differ between two runs, so a digest mismatch says what diverged. */
    String divergence(UUID first, UUID second, UUID caseKey) {
        var a = steps(first, caseKey);
        var b = steps(second, caseKey);
        for (var index = 0; index < Math.min(a.size(), b.size()); index++) {
            for (var column : List.of("window_key", "decision", "observation")) {
                var left = String.valueOf(a.get(index).get(column));
                var right = String.valueOf(b.get(index).get(column));
                if (!left.equals(right)) {
                    var at = IntStream.range(0, Math.min(left.length(), right.length()))
                            .filter(i -> left.charAt(i) != right.charAt(i))
                            .findFirst()
                            .orElse(Math.min(left.length(), right.length()));
                    return "step %d era %s round %s phase %s (%s) differs at char %d: [%s] vs [%s] EVENTS: %s"
                            .formatted(
                                    index,
                                    a.get(index).get("era"),
                                    a.get(index).get("round"),
                                    a.get(index).get("phase"),
                                    column,
                                    at,
                                    excerpt(left, at),
                                    excerpt(right, at),
                                    eventDivergence(first, second, caseKey));
                }
            }
        }
        return "no step differs; steps %d vs %d".formatted(a.size(), b.size());
    }

    /** The first event of each topic that differs between the two games, with the payloads around it. */
    private String eventDivergence(UUID first, UUID second, UUID caseKey) {
        var a = events(first, caseKey);
        var b = events(second, caseKey);
        var report = new StringBuilder();
        for (var topic : List.of("game.events:", "timeline.events:")) {
            var left = a.stream().filter(event -> event.startsWith(topic)).toList();
            var right = b.stream().filter(event -> event.startsWith(topic)).toList();
            var index = IntStream.range(0, Math.min(left.size(), right.size()))
                    .filter(i -> !left.get(i).equals(right.get(i)))
                    .findFirst()
                    .orElse(-1);
            report.append("%n%s first difference at %d (sizes %d vs %d): A=%s B=%s"
                    .formatted(topic, index, left.size(), right.size(), around(left, index), around(right, index)));
        }
        return report.toString();
    }

    private static List<String> around(List<String> events, int index) {
        if (index < 0) {
            return List.of();
        }
        return events.subList(Math.max(0, index - 1), Math.min(events.size(), Math.max(0, index) + 1)).stream()
                .map(event -> event.length() > 1200 ? event.substring(0, 1200) : event)
                .toList();
    }

    private List<String> events(UUID runId, UUID caseKey) {
        return jdbc.queryForList(
                "SELECT e.source || ':' || e.event_type || '|' || convert_from(r.content, 'UTF8')"
                        + " FROM evidence_event e JOIN evidence_artifact r ON r.digest = e.payload_artifact"
                        + " JOIN case_attempt a ON a.attempt_id = e.attempt_id JOIN run_case c ON c.case_id = a.case_id"
                        + " WHERE c.run_id = ? AND c.case_key = ? AND a.state = 'SUCCEEDED'"
                        + " ORDER BY e.source, e.partition_no, e.offset_no",
                String.class,
                runId,
                caseKey);
    }

    private static String excerpt(String text, int at) {
        var from = at < 200 ? 0 : Math.max(0, at - 80);
        return text.substring(from, Math.min(text.length(), from + 1400));
    }

    private List<Map<String, Object>> steps(UUID runId, UUID caseKey) {
        return jdbc.queryForList(
                "SELECT s.window_key, s.decision, s.observation, s.era, s.round, s.phase FROM evidence_step s"
                        + " JOIN case_attempt a ON a.attempt_id = s.attempt_id JOIN run_case c ON c.case_id = a.case_id"
                        + " WHERE c.run_id = ? AND c.case_key = ? AND a.state = 'SUCCEEDED'"
                        + " ORDER BY s.step, s.seat_index",
                runId,
                caseKey);
    }
    /** The attempts that failed, as their failure code and message, so an unplayed run says why. */
    List<String> failures(UUID runId) {
        return jdbc.queryForList(
                "SELECT a.failure_code || ': ' || a.failure_message FROM case_attempt a JOIN run_case c ON c.case_id ="
                        + " a.case_id WHERE c.run_id = ? AND a.failure_code IS NOT NULL",
                String.class,
                runId);
    }

    List<String> lanes(UUID runId) {
        return jdbc.queryForList(
                "SELECT DISTINCT a.lane_id FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id"
                        + " WHERE c.run_id = ? ORDER BY 1",
                String.class,
                runId);
    }

    private static String digestOf(String resultJson) {
        var marker = "\"semanticDigest\":\"";
        var start = resultJson.indexOf(marker) + marker.length();
        return resultJson.substring(start, resultJson.indexOf('"', start));
    }
}
