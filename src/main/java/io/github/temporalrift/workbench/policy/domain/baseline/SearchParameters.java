package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.github.temporalrift.workbench.policy.domain.baseline.FactionPreferences.Bias;
import io.github.temporalrift.workbench.policy.domain.baseline.FactionPreferences.Preference;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The parameters of {@code faction-search}: every preference, lean, threshold and spread behavior the standard bots
 * hold fixed, as integers on a declared grid. A parameter left out reads as its default, and the defaults are the
 * values {@code faction-spread} holds, so {@link #DEFAULTS} decides exactly as it does.
 *
 * <p>Keys: {@code affinity.card.<FACTION>.<CARD>}, {@code affinity.special.<FACTION>.<ACTION>},
 * {@code lean.support.<FACTION>} (Push and Swing), {@code lean.suppress.<FACTION>} (a lean is 0 leading, 1 trailing, 2
 * neutral), {@code threshold.*} and {@code spread.*} (a switch is 0 or 1).
 */
public final class SearchParameters {

    /** The parameters of {@code faction-spread}: every value at its default. */
    public static final SearchParameters DEFAULTS = new SearchParameters(Map.of(), null);

    public static final String CHAIN_THREAT_LINKS = "threshold.chainThreatLinks";
    public static final String TAPESTRY_LINKS = "threshold.tapestryLinks";
    public static final String STAKE_VALUE = "threshold.stakeValue";
    public static final String COMMITMENT_BONUS = "threshold.commitmentBonus";
    public static final String DECLARED_BY_OTHER_PENALTY = "threshold.declaredByOtherPenalty";
    public static final String PROTECT_AFFINITY = "threshold.protectAffinity";
    public static final String GUARD_AFFINITY = "threshold.guardAffinity";
    public static final String COUNTER_AFFINITY = "threshold.counterAffinity";
    public static final String CHAIN_BREAK_MARGIN = "threshold.chainBreakMargin";
    public static final String BET_SPREADING = "spread.betSpreading";
    public static final String RUNNER_UP_PERCENT = "spread.runnerUpPercent";
    public static final String CONTEST_CHAIN = "spread.contestChain";
    public static final String AVOID_PUBLIC_LINK = "spread.avoidPublicLink";
    public static final String DETONATE = "spread.detonate";

    private static final int MAX_AFFINITY = 8;
    private static final Map<String, Spec> GLOBALS = new LinkedHashMap<>();
    private static final List<Spec> SCHEMA = buildSchema();
    private static final Map<String, Spec> BY_KEY = indexed(SCHEMA);

    private final Map<String, Integer> values;
    private final Faction scope;

    private SearchParameters(Map<String, Integer> values, Faction scope) {
        this.values = new TreeMap<>(values);
        this.scope = scope;
    }

    /**
     * The key of a threshold or spread behavior for one faction: these parameters act only on the seat that plays that
     * faction, so a table of one parameter set changes the play of one faction and leaves the others as they are.
     */
    public static String scoped(String base, Faction faction) {
        return base + "." + faction;
    }

    /** These parameters read for the seat that plays {@code faction}: its thresholds and behaviors apply. */
    SearchParameters forFaction(Faction faction) {
        return new SearchParameters(values, faction);
    }

    /** One declared parameter: its grid {@code min + k * step} up to {@code max}, and its default. */
    public record Spec(String key, int min, int max, int step, int defaultValue) {

        public boolean accepts(int value) {
            return value >= min && value <= max && (value - min) % step == 0;
        }
    }

    /** Why a parameter set is refused, naming the parameter. */
    public static final class Refused extends IllegalArgumentException {

        private final String parameter;

        Refused(String parameter, String message) {
            super(message);
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }
    }

    /** Every declared parameter in canonical (key) order. */
    public static List<Spec> schema() {
        return SCHEMA;
    }

    public static Optional<Spec> spec(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /**
     * The parameters naming these values; a value at its default is dropped so equal parameter sets are equal.
     *
     * @throws Refused when a key is not declared, or a value is outside its range or off its step
     */
    public static SearchParameters of(Map<String, Integer> named) {
        var kept = new LinkedHashMap<String, Integer>();
        for (var entry : named.entrySet()) {
            var spec = BY_KEY.get(entry.getKey());
            if (spec == null) {
                throw new Refused(entry.getKey(), "unknown parameter " + entry.getKey());
            }
            if (!spec.accepts(entry.getValue())) {
                throw new Refused(
                        entry.getKey(),
                        entry.getKey() + " must lie in [" + spec.min() + ", " + spec.max() + "] on a step of "
                                + spec.step() + ": " + entry.getValue());
            }
            if (entry.getValue() != spec.defaultValue()) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return new SearchParameters(kept, null);
    }

    /** The values that differ from their defaults, by key. */
    public Map<String, Integer> overrides() {
        return Map.copyOf(values);
    }

    /** The canonical text the artifact digest hashes: the non-default values in key order. */
    public String canonical() {
        var parts = new ArrayList<String>();
        values.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join(",", parts);
    }

    /**
     * A parameter's value. A threshold or spread behavior (a base key) reads the one of the faction this view is for,
     * and its default when the view is for none.
     */
    int value(String key) {
        var global = GLOBALS.get(key);
        if (global != null) {
            if (scope == null) {
                return global.defaultValue();
            }
            key = scoped(key, scope);
        }
        var override = values.get(key);
        return override != null ? override : BY_KEY.get(key).defaultValue();
    }

    boolean enabled(String key) {
        return value(key) != 0;
    }

    /** The preference of a faction for a card type: its affinity and the lean its type takes. */
    Preference card(Faction faction, CardType type) {
        var base = FactionPreferences.card(faction, type);
        var affinity = value(cardKey(faction, type));
        var bias = switch (type) {
            case PUSH, SWING -> bias(value("lean.support." + faction));
            case SUPPRESS -> bias(value("lean.suppress." + faction));
            default -> base.bias();
        };
        return new Preference(affinity, bias);
    }

    /** The preference of a faction for a special action. */
    Preference special(Faction faction, SpecialAction action) {
        var base = FactionPreferences.special(faction, action);
        return new Preference(value(specialKey(faction, action)), base.bias());
    }

    public static String cardKey(Faction faction, CardType type) {
        return "affinity.card." + faction + "." + type;
    }

    public static String specialKey(Faction faction, SpecialAction action) {
        return "affinity.special." + faction + "." + action;
    }

    private static Bias bias(int lean) {
        return switch (lean) {
            case 0 -> Bias.LEADING;
            case 1 -> Bias.TRAILING;
            default -> Bias.NEUTRAL;
        };
    }

    private static int lean(Bias bias) {
        return switch (bias) {
            case LEADING -> 0;
            case TRAILING -> 1;
            case NEUTRAL -> 2;
        };
    }

    private static void global(Spec spec) {
        GLOBALS.put(spec.key(), spec);
    }

    private static List<Spec> buildSchema() {
        var specs = new ArrayList<Spec>();
        for (var faction : Faction.values()) {
            for (var type : CardType.values()) {
                specs.add(new Spec(
                        cardKey(faction, type),
                        0,
                        MAX_AFFINITY,
                        1,
                        FactionPreferences.card(faction, type).affinity()));
            }
            for (var action : SpecialAction.values()) {
                specs.add(new Spec(
                        specialKey(faction, action),
                        0,
                        MAX_AFFINITY,
                        1,
                        FactionPreferences.special(faction, action).affinity()));
            }
            specs.add(new Spec(
                    "lean.support." + faction,
                    0,
                    2,
                    1,
                    lean(FactionPreferences.card(faction, CardType.PUSH).bias())));
            specs.add(new Spec(
                    "lean.suppress." + faction,
                    0,
                    2,
                    1,
                    lean(FactionPreferences.card(faction, CardType.SUPPRESS).bias())));
        }
        global(new Spec(CHAIN_THREAT_LINKS, 1, 4, 1, ChainContest.THREATENING_LINKS));
        global(new Spec(TAPESTRY_LINKS, 1, 4, 1, FactionRecipePolicy.TAPESTRY_LINKS));
        global(new Spec(STAKE_VALUE, 0, 8, 1, ParadoxAwarePolicy.COMMITMENT_VALUE));
        global(new Spec(COMMITMENT_BONUS, 0, 600, 100, FactionRecipePolicy.COMMITMENT_BONUS));
        global(new Spec(DECLARED_BY_OTHER_PENALTY, 0, 600, 100, FactionRecipePolicy.DECLARED_BY_OTHER_PENALTY));
        global(new Spec(PROTECT_AFFINITY, 0, 9, 1, FactionRecipePolicy.PROTECT_AFFINITY));
        global(new Spec(GUARD_AFFINITY, 0, 9, 1, FactionRecipePolicy.GUARD_AFFINITY));
        global(new Spec(COUNTER_AFFINITY, 0, 9, 1, FactionRecipePolicy.COUNTER_AFFINITY));
        global(new Spec(CHAIN_BREAK_MARGIN, 0, 3, 1, ParadoxAwarePolicy.CHAIN_BREAK_MARGIN));
        global(new Spec(BET_SPREADING, 0, 1, 1, 1));
        global(new Spec(RUNNER_UP_PERCENT, 0, 100, 25, 50));
        global(new Spec(CONTEST_CHAIN, 0, 1, 1, 1));
        global(new Spec(AVOID_PUBLIC_LINK, 0, 1, 1, 1));
        global(new Spec(DETONATE, 0, 1, 1, 1));
        GLOBALS.values().forEach(spec -> {
            for (var faction : Faction.values()) {
                specs.add(new Spec(
                        scoped(spec.key(), faction), spec.min(), spec.max(), spec.step(), spec.defaultValue()));
            }
        });
        return specs.stream().sorted(java.util.Comparator.comparing(Spec::key)).toList();
    }

    private static Map<String, Spec> indexed(List<Spec> specs) {
        var byKey = new LinkedHashMap<String, Spec>();
        specs.forEach(spec -> byKey.put(spec.key(), spec));
        return byKey;
    }
}
