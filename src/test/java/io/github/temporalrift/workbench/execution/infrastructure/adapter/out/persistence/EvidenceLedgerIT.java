package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.workbench.StatementCounter;
import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.text.Sha256;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

@WorkbenchIntegrationTest
class EvidenceLedgerIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID SCOPE = new UUID(1, 1);
    private static final UUID GAME = new UUID(2, 2);
    private static final UUID ATTEMPT = new UUID(3, 3);
    private static final WindowRef HAND = WindowRef.of(WindowKind.HAND_SELECTION, 1);
    private static final String GAME_TOPIC = "game.events";
    private static final String TIMELINE_TOPIC = "timeline.events";

    @Autowired
    private EvidenceLedger evidence;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StatementCounter counter;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM evidence_event");
        jdbc.update("DELETE FROM evidence_step");
        jdbc.update("DELETE FROM evidence_source_offset");
        jdbc.update("DELETE FROM case_evidence");
        jdbc.update("DELETE FROM evidence_artifact");
    }

    @Test
    void aDuplicateDeliveryOfTheSameSourceAndEventCountsOnce() {
        var event = event(GAME_TOPIC, 0, 7, new UUID(9, 1), "ScoresUpdated", "{\"updates\":[]}");

        assertThat(evidence.retain(SCOPE, ATTEMPT, event)).isTrue();
        assertThat(evidence.retain(SCOPE, ATTEMPT, event)).isFalse();
        assertThat(evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 0, 11, new UUID(9, 1), "ScoresUpdated", "{}")))
                .isFalse();

        assertThat(evidence.events(SCOPE, GAME)).singleElement().satisfies(kept -> {
            assertThat(kept.offset()).isEqualTo(7);
            assertThat(kept.payload()).isEqualTo("{\"updates\":[]}");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theSameEventIdentifierOnAnotherSourceIsAnotherEvent() {
        var id = new UUID(9, 2);

        assertThat(evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 0, 1, id, "A", "{}")))
                .isTrue();
        assertThat(evidence.retain(SCOPE, ATTEMPT, event(TIMELINE_TOPIC, 0, 1, id, "A", "{}")))
                .isTrue();

        assertThat(evidence.events(SCOPE, GAME)).hasSize(2);
    }

    @Test
    void eventsAreListedPerSourceInOffsetOrderWithNoOrderAcrossSourcesImplied() {
        evidence.retain(SCOPE, ATTEMPT, event(TIMELINE_TOPIC, 0, 0, new UUID(9, 3), "T", "{}"));
        evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 1, 4, new UUID(9, 4), "G2", "{}"));
        evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 0, 9, new UUID(9, 5), "G1", "{}"));
        evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 0, 2, new UUID(9, 6), "G0", "{}"));

        assertThat(evidence.events(SCOPE, GAME))
                .extracting(ObservedEvent::eventType)
                .containsExactly("G0", "G1", "G2", "T");
    }

    @Test
    void sourceOffsetsOnlyMoveForward() {
        evidence.advance(SCOPE, GAME, GAME_TOPIC, 0, 10);
        evidence.advance(SCOPE, GAME, GAME_TOPIC, 0, 4);
        evidence.advance(SCOPE, GAME, GAME_TOPIC, 1, 3);
        evidence.advance(SCOPE, GAME, TIMELINE_TOPIC, 0, 8);

        assertThat(evidence.offsets(SCOPE, GAME))
                .containsEntry(new EvidenceLedger.SourcePartition(GAME_TOPIC, 0), 10L)
                .containsEntry(new EvidenceLedger.SourcePartition(GAME_TOPIC, 1), 3L)
                .containsEntry(new EvidenceLedger.SourcePartition(TIMELINE_TOPIC, 0), 8L);
    }

    @Test
    void aBatchOfHundredEventsIsStoredWithOneArtifactInsertAndOneEventInsert() {
        var hibernate = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        hibernate.setStatisticsEnabled(true);
        var batch = distinctEvents(100);
        var before = counter.executed();
        var prepared = hibernate.getPrepareStatementCount();

        evidence.retainAll(SCOPE, ATTEMPT, batch);

        assertThat(counter.executed() - before).isEqualTo(2);
        assertThat(hibernate.getPrepareStatementCount() - prepared).isZero();
        assertThat(count("evidence_event")).isEqualTo(100);
        assertThat(count("evidence_artifact")).isEqualTo(100);
    }

    @Test
    void retainingTheSameBatchTwiceStoresItOnce() {
        var batch = distinctEvents(100);

        evidence.retainAll(SCOPE, ATTEMPT, batch);
        evidence.retainAll(SCOPE, ATTEMPT, batch);

        assertThat(count("evidence_event")).isEqualTo(100);
        assertThat(count("evidence_artifact")).isEqualTo(100);
    }

    @Test
    void eventsSharingAPayloadShareOneArtifactAndARepeatedEventInABatchIsOneEvent() {
        var first = event(GAME_TOPIC, 0, 1, new UUID(9, 20), "A", "{\"same\":true}");
        var second = event(GAME_TOPIC, 0, 2, new UUID(9, 21), "B", "{\"same\":true}");

        evidence.retainAll(SCOPE, ATTEMPT, List.of(first, second, first));

        assertThat(count("evidence_event")).isEqualTo(2);
        assertThat(count("evidence_artifact")).isEqualTo(1);
    }

    @Test
    void anEmptyBatchIssuesNoStatement() {
        var before = counter.executed();

        evidence.retainAll(SCOPE, ATTEMPT, List.of());
        evidence.advanceAll(SCOPE, GAME, Map.of());

        assertThat(counter.executed()).isEqualTo(before);
    }

    @Test
    void offsetsAreUpsertedTogetherAndNeverMoveBack() {
        var before = counter.executed();

        evidence.advanceAll(
                SCOPE,
                GAME,
                Map.of(
                        new EvidenceLedger.SourcePartition(GAME_TOPIC, 0), 10L,
                        new EvidenceLedger.SourcePartition(GAME_TOPIC, 1), 3L));
        assertThat(counter.executed() - before).isEqualTo(1);

        evidence.advanceAll(
                SCOPE,
                GAME,
                Map.of(
                        new EvidenceLedger.SourcePartition(GAME_TOPIC, 0), 4L,
                        new EvidenceLedger.SourcePartition(GAME_TOPIC, 1), 7L));

        assertThat(evidence.offsets(SCOPE, GAME))
                .containsEntry(new EvidenceLedger.SourcePartition(GAME_TOPIC, 0), 10L)
                .containsEntry(new EvidenceLedger.SourcePartition(GAME_TOPIC, 1), 7L);
    }

    @Test
    void theFirstEventOfATypeIsFoundWithoutVerifyingAnyOtherPayload() {
        var ended = event(GAME_TOPIC, 0, 5, new UUID(9, 30), "GameEnded", "{\"ended\":true}");
        var other = event(GAME_TOPIC, 0, 6, new UUID(9, 31), "ScoresUpdated", "{\"scores\":[]}");
        evidence.retainAll(SCOPE, ATTEMPT, List.of(other, ended));
        jdbc.update("UPDATE evidence_artifact SET content = ? WHERE digest = ?", new byte[] {1}, digestOf(other));

        assertThat(evidence.firstEventOfType(SCOPE, GAME, "GameEnded"))
                .hasValueSatisfying(found -> assertThat(found.payload()).isEqualTo("{\"ended\":true}"));
        assertThat(evidence.firstEventOfType(SCOPE, GAME, "Missing")).isEmpty();

        jdbc.update("UPDATE evidence_artifact SET content = ? WHERE digest = ?", new byte[] {1}, digestOf(ended));
        assertThatThrownBy(() -> evidence.firstEventOfType(SCOPE, GAME, "GameEnded"))
                .isInstanceOf(ManifestMismatchException.class);
    }

    @Test
    void anEventsPageIsAWindowOfTheFullListingAndOnlyVerifiesItsOwnPayloads() {
        var all = new ArrayList<ObservedEvent>();
        for (var i = 0; i < 12; i++) {
            all.add(event(
                    i % 2 == 0 ? TIMELINE_TOPIC : GAME_TOPIC,
                    i % 3,
                    i,
                    new UUID(9, 200L + i),
                    "E",
                    "{\"n\":" + i + "}"));
        }
        evidence.retainAll(SCOPE, ATTEMPT, all);
        var listed = evidence.events(SCOPE, GAME);
        var outside = listed.get(0);
        jdbc.update("UPDATE evidence_artifact SET content = ? WHERE digest = ?", new byte[] {1}, digestOf(outside));

        var page = evidence.eventsPage(SCOPE, GAME, 3, 4);

        assertThat(page.events()).isEqualTo(listed.subList(3, 7));
        assertThat(page.more()).isTrue();
        assertThat(evidence.eventsPage(SCOPE, GAME, 8, 4).more()).isFalse();
        assertThat(evidence.eventsPage(SCOPE, GAME, 8, 4).events()).isEqualTo(listed.subList(8, 12));
        assertThat(evidence.eventsPage(SCOPE, GAME, 12, 4).events()).isEmpty();
        assertThatThrownBy(() -> evidence.eventsPage(SCOPE, GAME, 0, 1)).isInstanceOf(ManifestMismatchException.class);
    }

    @Test
    void aStepsPageIsFilteredBySeatAndCursorAndLimitedInTheQuery() {
        for (var i = 0; i < 9; i++) {
            evidence.append(SCOPE, GAME, ATTEMPT, step(i % 3, HAND, StepOutcome.ACCEPTED));
        }

        assertThat(evidence.stepsPage(SCOPE, GAME, null, 2, 4))
                .extracting(StepRecord::step)
                .containsExactly(3, 4, 5, 6);
        assertThat(evidence.stepsPage(SCOPE, GAME, 1, 1, 2))
                .extracting(StepRecord::step)
                .containsExactly(4, 7);
        assertThat(evidence.stepsPage(SCOPE, GAME, null, -1, 2))
                .extracting(StepRecord::step)
                .containsExactly(0, 1);
        assertThat(evidence.lastStep(SCOPE, GAME)).hasValue(8);
        assertThat(evidence.lastStep(SCOPE, new UUID(5, 5))).isEmpty();
    }

    @Test
    void theManifestDigestIsReadFromThePinnedRowEvenBeforeItIsSealed() {
        assertThat(evidence.manifestDigest(SCOPE)).isEmpty();

        evidence.pin(SCOPE, "a".repeat(64), "{}", NOW);

        assertThat(evidence.manifestDigest(SCOPE)).hasValue("a".repeat(64));
    }

    @Test
    void stepsKeepTheOrderTheyWereSentAndAnUnacknowledgedOneIsResolvedLater() {
        assertThat(evidence.append(SCOPE, GAME, ATTEMPT, step(0, HAND, StepOutcome.UNACKNOWLEDGED)))
                .isZero();
        assertThat(evidence.append(SCOPE, GAME, ATTEMPT, step(1, HAND, StepOutcome.REJECTED)))
                .isEqualTo(1);

        evidence.resolve(SCOPE, GAME, 0, HAND, StepOutcome.ACCEPTED, null);

        assertThat(evidence.steps(SCOPE, GAME))
                .extracting(kept -> kept.seatIndex() + ":" + kept.outcome())
                .containsExactly("0:ACCEPTED", "1:REJECTED");
    }

    @Test
    void aGameTheRunnerAbandonedStaysOnRecordNextToTheOneThatCounted() {
        var abandoned = new UUID(2, 3);
        evidence.append(SCOPE, abandoned, ATTEMPT, step(0, HAND, StepOutcome.ACCEPTED));
        evidence.append(SCOPE, GAME, new UUID(3, 4), step(0, HAND, StepOutcome.ACCEPTED));

        assertThat(evidence.steps(SCOPE, abandoned)).hasSize(1);
        assertThat(evidence.steps(SCOPE, GAME)).hasSize(1);
    }

    @Test
    void pinnedArtifactsReadBackAndAreOnlyAvailableOnceSealed() {
        evidence.pin(SCOPE, "a".repeat(64), "{\"name\":\"experiment\"}", NOW);
        assertThat(evidence.pinned(SCOPE)).isEmpty();

        evidence.seal(SCOPE, "0\tera1/hand-selection\tpass", "b".repeat(64), NOW);

        assertThat(evidence.pinned(SCOPE)).hasValueSatisfying(pinned -> {
            assertThat(pinned.manifestDigest()).isEqualTo("a".repeat(64));
            assertThat(pinned.manifestJson()).isEqualTo("{\"name\":\"experiment\"}");
            assertThat(pinned.transcript()).isEqualTo("0\tera1/hand-selection\tpass");
            assertThat(pinned.resultDigest()).isEqualTo("b".repeat(64));
        });
    }

    @Test
    void pinningAgainKeepsTheFirstManifest() {
        evidence.pin(SCOPE, "a".repeat(64), "{\"first\":true}", NOW);
        evidence.pin(SCOPE, "c".repeat(64), "{\"second\":true}", NOW);
        evidence.seal(SCOPE, "", "b".repeat(64), NOW);

        assertThat(evidence.pinned(SCOPE).orElseThrow().manifestJson()).isEqualTo("{\"first\":true}");
    }

    @Test
    void anArtifactThatNoLongerMatchesItsContentAddressIsRefused() {
        evidence.pin(SCOPE, "a".repeat(64), "{}", NOW);
        evidence.seal(SCOPE, "0\twindow\tpass", "b".repeat(64), NOW);
        jdbc.update(
                "UPDATE evidence_artifact SET content = ? WHERE digest = (SELECT transcript_artifact FROM"
                        + " case_evidence WHERE scope_id = ?)",
                "altered".getBytes(StandardCharsets.UTF_8),
                SCOPE);

        assertThatThrownBy(() -> evidence.pinned(SCOPE))
                .isInstanceOf(ManifestMismatchException.class)
                .hasMessageContaining("content address");
    }

    @Test
    void purgingAScopeForgetsEverythingRetainedForIt() {
        evidence.pin(SCOPE, "a".repeat(64), "{}", NOW);
        evidence.append(SCOPE, GAME, ATTEMPT, step(0, HAND, StepOutcome.ACCEPTED));
        evidence.retain(SCOPE, ATTEMPT, event(GAME_TOPIC, 0, 1, new UUID(9, 7), "A", "{}"));
        evidence.advance(SCOPE, GAME, GAME_TOPIC, 0, 2);

        evidence.purge(SCOPE);

        assertThat(evidence.steps(SCOPE, GAME)).isEmpty();
        assertThat(evidence.events(SCOPE, GAME)).isEmpty();
        assertThat(evidence.offsets(SCOPE, GAME)).isEmpty();
        assertThat(evidence.pinned(SCOPE)).isEmpty();
    }

    private static List<ObservedEvent> distinctEvents(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> event(GAME_TOPIC, 0, i, new UUID(9, 100L + i), "E", "{\"n\":" + i + "}"))
                .toList();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String digestOf(ObservedEvent event) {
        return Sha256.hex(event.payload().getBytes(StandardCharsets.UTF_8));
    }

    private static ObservedEvent event(
            String source, int partition, long offset, UUID eventId, String type, String payload) {
        return new ObservedEvent(
                source, partition, offset, eventId, type, new UUID(8, 8), "Game", GAME, NOW, 1, payload);
    }

    private static StepRecord step(int seat, WindowRef window, StepOutcome outcome) {
        var observation = new EntitledObservation(
                seat, Faction.ERASERS, List.of(), List.of(), new DecisionWindow.HandSelection(1, List.of(), 1));
        return new StepRecord(0, seat, window, NOW, observation, new Candidate.Pass(), outcome, null, null);
    }
}
