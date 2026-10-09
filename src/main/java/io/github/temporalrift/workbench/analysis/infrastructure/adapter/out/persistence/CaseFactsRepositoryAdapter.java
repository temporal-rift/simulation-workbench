package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.GameFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.SeatFacts;
import io.github.temporalrift.workbench.analysis.domain.game.CardGrade;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CardType;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;

/** PostgreSQL case facts, one JSON document per case and analysis version. */
public class CaseFactsRepositoryAdapter implements CaseFactsRepository {

    private static final String CARD_SEPARATOR = "/";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CaseFactsRepositoryAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Map<UUID, CaseFacts> find(Collection<UUID> caseIds, String analysisVersion) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        var found = new HashMap<UUID, CaseFacts>();
        jdbc.query(
                        "SELECT facts FROM analysis_case_fact WHERE analysis_version = :version AND case_id IN (:ids)",
                        new MapSqlParameterSource()
                                .addValue("version", analysisVersion)
                                .addValue("ids", caseIds),
                        (rs, i) -> read(rs.getString("facts")))
                .forEach(facts -> found.put(facts.caseId(), facts));
        return found;
    }

    @Override
    public void save(CaseFacts facts, String analysisVersion) {
        jdbc.update(
                "INSERT INTO analysis_case_fact (case_id, analysis_version, facts, created_at)"
                        + " VALUES (:id, :version, :facts, :at) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource()
                        .addValue("id", facts.caseId())
                        .addValue("version", analysisVersion)
                        .addValue("facts", write(facts))
                        .addValue("at", Timestamp.from(clock.instant())));
    }

    private String write(CaseFacts facts) {
        try {
            return objectMapper.writeValueAsString(new CaseDocument(
                    facts.caseId(),
                    facts.game(),
                    facts.seats().stream().map(SeatDocument::of).toList()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot store the facts of case " + facts.caseId(), e);
        }
    }

    private CaseFacts read(String json) {
        try {
            var document = objectMapper.readValue(json, CaseDocument.class);
            return new CaseFacts(
                    document.caseId(),
                    document.game(),
                    document.seats().stream().map(SeatDocument::facts).toList());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored case facts are unreadable", e);
        }
    }

    record CaseDocument(UUID caseId, GameFacts game, List<SeatDocument> seats) {}

    /** Seat facts with card counts keyed by text, since a JSON object key must be a string. */
    record SeatDocument(
            int seat,
            Faction faction,
            boolean won,
            boolean sharedWin,
            int score,
            Map<String, Integer> cardsOffered,
            Map<String, Integer> cardsKept,
            Map<String, Integer> cardsPlayed,
            Map<String, Integer> reactiveCardsOffered,
            Map<String, Integer> reactiveCardsPlayed,
            Map<String, Integer> knownCardRounds,
            Map<String, Integer> playableCardRounds,
            Map<String, Integer> unknownCardRounds,
            Map<SpecialAction, Integer> specialAttempts,
            Map<SpecialAction, Integer> specialSubmissionRejections,
            Map<SpecialAction, Integer> specialAccepts,
            Map<SpecialAction, Integer> specialResolutionRejections,
            int declarationOffers,
            Map<SpecialAction, Integer> declarations) {

        static SeatDocument of(SeatFacts facts) {
            return new SeatDocument(
                    facts.seat(),
                    facts.faction(),
                    facts.won(),
                    facts.sharedWin(),
                    facts.score(),
                    text(facts.cardsOffered()),
                    text(facts.cardsKept()),
                    text(facts.cardsPlayed()),
                    text(facts.reactiveCardsOffered()),
                    text(facts.reactiveCardsPlayed()),
                    text(facts.knownCardRounds()),
                    text(facts.playableCardRounds()),
                    text(facts.unknownCardRounds()),
                    facts.specialAttempts(),
                    facts.specialSubmissionRejections(),
                    facts.specialAccepts(),
                    facts.specialResolutionRejections(),
                    facts.declarationOffers(),
                    facts.declarations());
        }

        SeatFacts facts() {
            return new SeatFacts(
                    seat,
                    faction,
                    won,
                    sharedWin,
                    score,
                    cards(cardsOffered),
                    cards(cardsKept),
                    cards(cardsPlayed),
                    cards(reactiveCardsOffered),
                    cards(reactiveCardsPlayed),
                    cards(knownCardRounds),
                    cards(playableCardRounds),
                    cards(unknownCardRounds),
                    specialAttempts,
                    specialSubmissionRejections,
                    specialAccepts,
                    specialResolutionRejections,
                    declarationOffers,
                    declarations);
        }

        private static Map<String, Integer> text(Map<CardKey, Integer> counts) {
            return counts.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().type()
                                    + CARD_SEPARATOR
                                    + entry.getKey().grade(),
                            Map.Entry::getValue,
                            Integer::sum,
                            TreeMap::new));
        }

        private static Map<CardKey, Integer> cards(Map<String, Integer> counts) {
            return counts.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> key(entry.getKey()), Map.Entry::getValue, Integer::sum, TreeMap::new));
        }

        private static CardKey key(String text) {
            var parts = text.split(CARD_SEPARATOR);
            return new CardKey(CardType.valueOf(parts[0]), CardGrade.valueOf(parts[1]));
        }
    }
}
