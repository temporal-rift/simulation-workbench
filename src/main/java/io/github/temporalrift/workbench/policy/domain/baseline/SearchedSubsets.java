package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The parameters a hunt of each faction searches: the faction's own specials, the information cards and Obscure, the
 * shifters it uses to support its bet and its own thresholds. Every other parameter stays at its {@code faction-spread}
 * default, which keeps a search of about 16 dimensions dense enough for 64 candidates.
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
                specials(keys, faction, SpecialAction.ANNIHILATE, SpecialAction.CORRUPT, SpecialAction.CASCADE);
                cards(keys, faction, CardType.SUPPRESS, CardType.NULLIFY, CardType.DETONATE, CardType.PUSH);
                keys.addAll(List.of(
                        "lean.suppress." + faction,
                        SearchParameters.CHAIN_THREAT_LINKS,
                        SearchParameters.COUNTER_AFFINITY,
                        SearchParameters.CONTEST_CHAIN));
            }
            case PROPHETS -> {
                specials(keys, faction, SpecialAction.FORESIGHT, SpecialAction.SEAL, SpecialAction.FULFILLMENT);
                cards(keys, faction, CardType.PUSH, CardType.STABILIZE, CardType.AMPLIFY);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.RUNNER_UP_PERCENT,
                        SearchParameters.PROTECT_AFFINITY,
                        SearchParameters.GUARD_AFFINITY,
                        SearchParameters.BET_SPREADING));
            }
            case REVISIONISTS -> {
                specials(keys, faction, SpecialAction.REWRITE, SpecialAction.MIMIC);
                cards(keys, faction, CardType.PUSH, CardType.SWING, CardType.REDIRECT, CardType.STABILIZE);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.COUNTER_AFFINITY,
                        SearchParameters.COMMITMENT_BONUS,
                        SearchParameters.BET_SPREADING));
            }
            case WEAVERS -> {
                specials(keys, faction, SpecialAction.THREAD, SpecialAction.REWEAVE, SpecialAction.TAPESTRY);
                cards(keys, faction, CardType.PUSH, CardType.AMPLIFY, CardType.STABILIZE);
                keys.addAll(List.of(
                        "lean.support." + faction,
                        SearchParameters.TAPESTRY_LINKS,
                        SearchParameters.PROTECT_AFFINITY,
                        SearchParameters.COMMITMENT_BONUS));
            }
            case ACTIVISTS -> {
                specials(keys, faction, SpecialAction.EXPOSE, SpecialAction.RALLY, SpecialAction.MOMENTUM);
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
                        SearchParameters.COUNTER_AFFINITY,
                        SearchParameters.AVOID_PUBLIC_LINK));
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
