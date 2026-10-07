package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.EnumMap;
import java.util.Map;

import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

/**
 * The versioned preference table of {@code faction-greedy-v1}: how strongly a faction favors each
 * card and special, and which outcomes it aims them at. The table is part of the policy bundle's
 * digest, so changing a value requires a new bundle version.
 */
final class FactionPreferences {

    /** Which end of the known outcome weights an action is aimed at. */
    enum Bias {
        LEADING,
        TRAILING,
        NEUTRAL
    }

    record Preference(int affinity, Bias bias) {}

    private static final Preference DEFAULT_SPECIAL = new Preference(1, Bias.NEUTRAL);

    private static final Map<CardType, Preference> DEFAULT_CARDS = new EnumMap<>(CardType.class);
    private static final Map<Faction, Map<CardType, Preference>> CARDS = new EnumMap<>(Faction.class);
    private static final Map<Faction, Map<SpecialAction, Preference>> SPECIALS = new EnumMap<>(Faction.class);

    static {
        for (var type : CardType.values()) {
            DEFAULT_CARDS.put(type, new Preference(1, Bias.NEUTRAL));
        }
        DEFAULT_CARDS.put(CardType.PUSH, new Preference(2, Bias.LEADING));
        DEFAULT_CARDS.put(CardType.SWING, new Preference(2, Bias.LEADING));
        DEFAULT_CARDS.put(CardType.SUPPRESS, new Preference(1, Bias.TRAILING));
        for (var faction : Faction.values()) {
            CARDS.put(faction, new EnumMap<>(CardType.class));
            SPECIALS.put(faction, new EnumMap<>(SpecialAction.class));
        }

        card(Faction.ERASERS, CardType.SUPPRESS, 4, Bias.LEADING);
        card(Faction.ERASERS, CardType.NULLIFY, 3, Bias.NEUTRAL);
        card(Faction.ERASERS, CardType.COLLIDE, 2, Bias.NEUTRAL);
        card(Faction.ERASERS, CardType.JAM, 2, Bias.NEUTRAL);
        card(Faction.ERASERS, CardType.STALL, 2, Bias.NEUTRAL);
        card(Faction.ERASERS, CardType.DETONATE, 5, Bias.NEUTRAL);
        special(Faction.ERASERS, SpecialAction.ANNIHILATE, 6, Bias.LEADING);
        special(Faction.ERASERS, SpecialAction.CORRUPT, 3, Bias.NEUTRAL);
        special(Faction.ERASERS, SpecialAction.CASCADE, 2, Bias.NEUTRAL);

        card(Faction.PROPHETS, CardType.PUSH, 4, Bias.LEADING);
        card(Faction.PROPHETS, CardType.SCAN, 3, Bias.NEUTRAL);
        card(Faction.PROPHETS, CardType.AMPLIFY, 2, Bias.NEUTRAL);
        card(Faction.PROPHETS, CardType.TRACE, 2, Bias.NEUTRAL);
        card(Faction.PROPHETS, CardType.INTERCEPT, 2, Bias.NEUTRAL);
        card(Faction.PROPHETS, CardType.STABILIZE, 5, Bias.LEADING);
        special(Faction.PROPHETS, SpecialAction.FORESIGHT, 5, Bias.LEADING);
        special(Faction.PROPHETS, SpecialAction.SEAL, 4, Bias.LEADING);
        special(Faction.PROPHETS, SpecialAction.FULFILLMENT, 3, Bias.LEADING);

        card(Faction.REVISIONISTS, CardType.PUSH, 4, Bias.LEADING);
        card(Faction.REVISIONISTS, CardType.SWING, 4, Bias.LEADING);
        card(Faction.REVISIONISTS, CardType.REDIRECT, 3, Bias.NEUTRAL);
        card(Faction.REVISIONISTS, CardType.STABILIZE, 2, Bias.LEADING);
        card(Faction.REVISIONISTS, CardType.DETONATE, 3, Bias.NEUTRAL);
        special(Faction.REVISIONISTS, SpecialAction.REWRITE, 5, Bias.LEADING);
        special(Faction.REVISIONISTS, SpecialAction.MIMIC, 3, Bias.LEADING);
        special(Faction.REVISIONISTS, SpecialAction.OBSCURE, 2, Bias.NEUTRAL);

        card(Faction.WEAVERS, CardType.PUSH, 4, Bias.LEADING);
        card(Faction.WEAVERS, CardType.AMPLIFY, 3, Bias.NEUTRAL);
        card(Faction.WEAVERS, CardType.TRACE, 2, Bias.NEUTRAL);
        card(Faction.WEAVERS, CardType.STABILIZE, 4, Bias.LEADING);
        special(Faction.WEAVERS, SpecialAction.THREAD, 6, Bias.LEADING);
        special(Faction.WEAVERS, SpecialAction.REWEAVE, 3, Bias.LEADING);
        special(Faction.WEAVERS, SpecialAction.TAPESTRY, 3, Bias.NEUTRAL);

        card(Faction.ACTIVISTS, CardType.PUSH, 4, Bias.LEADING);
        card(Faction.ACTIVISTS, CardType.AMPLIFY, 3, Bias.NEUTRAL);
        card(Faction.ACTIVISTS, CardType.JAM, 2, Bias.NEUTRAL);
        card(Faction.ACTIVISTS, CardType.DETONATE, 3, Bias.NEUTRAL);
        card(Faction.ACTIVISTS, CardType.STABILIZE, 2, Bias.LEADING);
        special(Faction.ACTIVISTS, SpecialAction.EXPOSE, 4, Bias.NEUTRAL);
    }

    private FactionPreferences() {}

    private static void card(Faction faction, CardType type, int affinity, Bias bias) {
        CARDS.get(faction).put(type, new Preference(affinity, bias));
    }

    private static void special(Faction faction, SpecialAction action, int affinity, Bias bias) {
        SPECIALS.get(faction).put(action, new Preference(affinity, bias));
    }

    static Preference card(Faction faction, CardType type) {
        return CARDS.get(faction).getOrDefault(type, DEFAULT_CARDS.get(type));
    }

    static Preference special(Faction faction, SpecialAction action) {
        return SPECIALS.get(faction).getOrDefault(action, DEFAULT_SPECIAL);
    }

    /** The effective table, rendered in a fixed order; hashed into the bundle's artifact digest. */
    static String canonicalDefinition() {
        var text = new StringBuilder("faction-greedy scoring: affinity*1000 + target score (0..100)\n");
        for (var faction : Faction.values()) {
            for (var type : CardType.values()) {
                var preference = card(faction, type);
                text.append(faction)
                        .append(" card ")
                        .append(type)
                        .append('=')
                        .append(preference)
                        .append('\n');
            }
            for (var action : SpecialAction.values()) {
                var preference = special(faction, action);
                text.append(faction)
                        .append(" special ")
                        .append(action)
                        .append('=')
                        .append(preference)
                        .append('\n');
            }
        }
        return text.toString();
    }
}
