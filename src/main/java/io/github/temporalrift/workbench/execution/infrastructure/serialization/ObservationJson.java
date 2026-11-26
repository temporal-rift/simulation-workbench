package io.github.temporalrift.workbench.execution.infrastructure.serialization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;

/**
 * The canonical JSON text of what a seat observed. Keys are sorted, so equal observations have equal text
 * and two runs can be compared as strings; the window carries its kind so the text reads back as the same
 * observation.
 */
public final class ObservationJson {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .addMixIn(DecisionWindow.class, WindowKinds.class)
            .addMixIn(EntitledObservation.class, BoardWhenPresent.class)
            .addMixIn(OutcomeView.class, BandWhenPublished.class)
            .addMixIn(PlayableSpecial.class, ReasonWhenUnavailable.class)
            .addMixIn(EventView.class, StalledWhenTrue.class)
            .addMixIn(Board.class, FactsWhenKnown.class)
            .addMixIn(Board.ChainView.class, ConfirmedEventsWhenKnown.class)
            .addMixIn(Board.Scores.class, DerivedScores.class)
            .addMixIn(TableRules.class, ChainRulesWhenKnown.class)
            .build();

    private ObservationJson() {}

    /** The canonical text of the observation. */
    public static String of(EntitledObservation observation) {
        return write(toMap(observation), observation);
    }

    /** The observation as a JSON object, for a response that embeds it. */
    public static Map<String, Object> toMap(EntitledObservation observation) {
        try {
            return MAPPER.convertValue(observation, OBJECT);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(description(observation) + " cannot be converted", e);
        }
    }

    /**
     * @throws IllegalStateException when the text is not an observation written by {@link #of}
     */
    public static EntitledObservation parse(String text) {
        try {
            return MAPPER.readValue(text, EntitledObservation.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("A retained observation cannot be read as JSON", e);
        }
    }

    private static String write(Map<String, Object> tree, EntitledObservation observation) {
        try {
            return MAPPER.writeValueAsString(tree);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + description(observation), e);
        }
    }

    private static String description(EntitledObservation observation) {
        return "the observation of seat " + observation.seatIndex();
    }

    /** Tells Jackson which window record a {@code kind} names. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DecisionWindow.HandSelection.class, name = "HandSelection"),
        @JsonSubTypes.Type(value = DecisionWindow.Declaration.class, name = "Declaration"),
        @JsonSubTypes.Type(value = DecisionWindow.ActionRound.class, name = "ActionRound"),
        @JsonSubTypes.Type(value = DecisionWindow.ParadoxResolution.class, name = "ParadoxResolution"),
        @JsonSubTypes.Type(value = DecisionWindow.TerminalReadiness.class, name = "TerminalReadiness")
    })
    private interface WindowKinds {}

    /**
     * An observation without board facts keeps the text it had before board facts existed, so retained texts
     * compare equal to new ones.
     */
    private interface BoardWhenPresent {
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = EmptyBoard.class)
        Board board();

        /** An observation without table rules keeps its earlier text. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TableRules rules();

        /** Derived from {@code rules}; never written. */
        @JsonIgnore
        TableRules tableRules();
    }

    /** A special the table does not report unavailable keeps the text it had before the report existed. */
    private interface ReasonWhenUnavailable {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String unavailableReason();

        /** Derived from {@code unavailableReason}; never written. */
        @JsonIgnore
        boolean unavailable();
    }

    /** An event that is not stalled keeps the text it had before the flag existed. */
    private interface StalledWhenTrue {
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean stalled();
    }

    /** A board without cascaded events or scores keeps the text it had before they existed. */
    private interface FactsWhenKnown {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<UUID> cascadedEventIds();

        @JsonInclude(JsonInclude.Include.NON_NULL)
        Board.Scores scores();
    }

    /** A chain whose confirmed link events are unknown keeps its earlier text. */
    private interface ConfirmedEventsWhenKnown {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<UUID> confirmedEventIds();
    }

    /** Derived from {@code own} and {@code others}; never written. */
    private interface DerivedScores {
        @JsonIgnore
        boolean leads();
    }

    /** Rules that do not say the chain length or the collapse limit keep their earlier text. */
    private interface ChainRulesWhenKnown {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer weaverChainLength();

        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer maxCascadedParadoxes();

        /** Derived from {@code weaverChainLength}; never written. */
        @JsonIgnore
        int chainLength();

        /** Derived from {@code maxCascadedParadoxes}; never written. */
        @JsonIgnore
        int collapseLimit();
    }

    /** An outcome whose band is not published keeps its earlier text. */
    private interface BandWhenPublished {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        PublicBand band();
    }

    /** Excludes the empty board; Jackson calls {@code equals} with the property value. */
    private static final class EmptyBoard {
        @Override
        public boolean equals(Object value) {
            return Board.EMPTY.equals(value);
        }

        @Override
        public int hashCode() {
            return Board.EMPTY.hashCode();
        }
    }
}
