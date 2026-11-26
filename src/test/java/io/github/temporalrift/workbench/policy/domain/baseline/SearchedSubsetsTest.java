package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class SearchedSubsetsTest {

    @Test
    void everyFactionSearchesTwelveToSixteenDistinctDeclaredParameters() {
        for (var faction : Faction.values()) {
            var subset = SearchedSubsets.of(faction);

            assertThat(subset).as(faction.name()).hasSizeBetween(12, 16).doesNotHaveDuplicates();
            assertThat(subset)
                    .allSatisfy(key ->
                            assertThat(SearchParameters.spec(key)).as(key).isPresent());
        }
    }

    @Test
    void everySubsetIncludesTheInformationCardsAndObscure() {
        for (var faction : Faction.values()) {
            assertThat(SearchedSubsets.of(faction))
                    .as(faction.name())
                    .contains(
                            SearchParameters.cardKey(faction, CardType.SCAN),
                            SearchParameters.cardKey(faction, CardType.INTERCEPT),
                            SearchParameters.cardKey(faction, CardType.DECOY),
                            SearchParameters.cardKey(faction, CardType.TRACE),
                            SearchParameters.specialKey(faction, SpecialAction.OBSCURE));
        }
    }

    @Test
    void aFactionSearchesOnlyItsOwnPreferences() {
        for (var faction : Faction.values()) {
            var others = List.of(Faction.values()).stream()
                    .filter(other -> other != faction)
                    .map(Faction::name)
                    .toList();

            assertThat(SearchedSubsets.of(faction))
                    .noneSatisfy(key -> assertThat(others).anyMatch(other -> key.contains("." + other)));
        }
    }

    @Test
    void theWeaversDoNotSearchSeal() {
        assertThat(SearchedSubsets.of(Faction.WEAVERS))
                .doesNotContain(SearchParameters.specialKey(Faction.WEAVERS, SpecialAction.SEAL));
    }
}
