package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The parameters a hunt of each faction searches: the faction's own specials, the information cards and Obscure, the
 * shifters it uses to support its bet and its own thresholds. Every other parameter stays at its {@code faction-spread}
 * default, which keeps a search of about 16 dimensions dense enough for 64 candidates. A special whose affinity the
 * recipe never reads (Corrupt, Expose, Tapestry and Reweave play at the counter or protect threshold) is not searched:
 * its threshold is.
 */
public final class SearchedSubsets {

    private SearchedSubsets() {}

    /** The searched parameters of a faction in key order, 12 to 16 of them. */
    public static List<String> of(Faction faction) {
        var keys = new ArrayList<String>();
        cards(keys, faction, CardType.SCAN, CardType.INTERCEPT, CardType.DECOY, CardType.TRACE);
        specials(keys, faction, SpecialAction.OBSCURE);
        switch (faction) {
            case ERASERS -> {
                specials(keys, faction, SpecialAction.ANNIHILATE, SpecialAction.CASCADE);
                cards(keys, faction, CardType.SUPPRESS, CardType.NULLIFY, CardType.DETONATE, CardType.PUSH);
                keys.addAll(List.of(
                        "lean.suppress." + faction,
                        SearchParameters.scoped(SearchParameters.CHAIN_THREAT_LINKS, faction),
                        SearchParameters.scoped(SearchParameters.COUNTER_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.CONTEST_CHAIN, faction)));
            }
            case PROPHETS -> {
                specials(keys, faction, SpecialAction.FORESIGHT, SpecialAction.SEAL, SpecialAction.FULFILLMENT);
                cards(keys, faction, CardType.PUSH, CardType.STABILIZE, CardType.AMPLIFY);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.scoped(SearchParameters.RUNNER_UP_PERCENT, faction),
                        SearchParameters.scoped(SearchParameters.PROTECT_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.GUARD_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.BET_SPREADING, faction)));
            }
            case REVISIONISTS -> {
                specials(keys, faction, SpecialAction.REWRITE, SpecialAction.MIMIC);
                cards(keys, faction, CardType.PUSH, CardType.SWING, CardType.REDIRECT, CardType.STABILIZE);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.scoped(SearchParameters.COUNTER_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.COMMITMENT_BONUS, faction),
                        SearchParameters.scoped(SearchParameters.BET_SPREADING, faction)));
            }
            case WEAVERS -> {
                specials(keys, faction, SpecialAction.THREAD);
                cards(keys, faction, CardType.PUSH, CardType.AMPLIFY, CardType.STABILIZE);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.scoped(SearchParameters.TAPESTRY_LINKS, faction),
                        SearchParameters.scoped(SearchParameters.PROTECT_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.COMMITMENT_BONUS, faction)));
            }
            case ACTIVISTS -> {
                specials(keys, faction, SpecialAction.RALLY, SpecialAction.MOMENTUM);
                cards(
                        keys,
                        faction,
                        CardType.PUSH,
                        CardType.AMPLIFY,
                        CardType.JAM,
                        CardType.DETONATE,
                        CardType.STABILIZE);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.scoped(SearchParameters.COUNTER_AFFINITY, faction),
                        SearchParameters.scoped(SearchParameters.AVOID_PUBLIC_LINK, faction)));
            }
        }
        return keys.stream().sorted().toList();
    }

    private static void specials(List<String> keys, Faction faction, SpecialAction... actions) {
        for (var action : actions) {
            keys.add(SearchParameters.specialKey(faction, action));
        }
    }

    private static void cards(List<String> keys, Faction faction, CardType... types) {
        for (var type : types) {
            keys.add(SearchParameters.cardKey(faction, type));
        }
    }
}
