package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.application.port.in.CampaignCommandOnceUseCase.Command;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.FidelityNotEstablishedException;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;

class CampaignCommandOnceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID KEY = new UUID(9, 9);

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final SimulatedPlay play = new SimulatedPlay();
    private final CampaignCommandOnceHandler once = new CampaignCommandOnceHandler(campaigns, CLOCK);
    private final PauseCampaignCommandHandler pause = new PauseCampaignCommandHandler(campaigns, play, CLOCK);
    private final ResumeCampaignCommandHandler resume = new ResumeCampaignCommandHandler(campaigns, play, CLOCK);
    private final SetCampaignParallelismCommandHandler parallelism =
            new SetCampaignParallelismCommandHandler(campaigns, play, CLOCK);
    private final StartConfirmationCommandHandler confirm =
            new StartConfirmationCommandHandler(campaigns, play, play, play, play);

    @Test
    void aRepeatedKeyDoesNotApplyTheCommandAgainAndReturnsTheCampaignAsItStands() {
        var id = running();
        once.handle(new Command(KEY, "pauseCampaign", id, ""), () -> pause.handle(id));
        resume.handle(id);

        var repeated = once.handle(new Command(KEY, "pauseCampaign", id, ""), () -> pause.handle(id));

        assertThat(repeated.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(state(id)).isEqualTo(CampaignState.RUNNING);
    }

    @Test
    void aConfirmationRepeatedAfterItsResponseWasLostReturnsTheConfirmingCampaign() {
        var id = searched();
        var first = once.handle(new Command(KEY, "startConfirmation", id, ""), () -> confirm.handle(id));

        var repeated = once.handle(new Command(KEY, "startConfirmation", id, ""), () -> confirm.handle(id));

        assertThat(repeated.state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(repeated.confirmation()).isEqualTo(first.confirmation());
    }

    @Test
    void aKeyReusedWithAnotherValueConflictsAndChangesNothing() {
        var id = running();
        once.handle(new Command(KEY, "setCampaignParallelism", id, "8"), () -> parallelism.handle(id, 8));

        var other = new Command(KEY, "setCampaignParallelism", id, "2");
        assertThatThrownBy(() -> once.handle(other, () -> parallelism.handle(id, 2)))
                .isInstanceOf(CampaignIdempotencyConflictException.class);

        assertThat(campaigns.find(id).orElseThrow().campaign().parallelism()).isEqualTo(8);
    }

    @Test
    void aKeyReusedOnAnotherOperationOrCampaignConflicts() {
        var id = running();
        var otherCampaign = running();
        once.handle(new Command(KEY, "pauseCampaign", id, ""), () -> pause.handle(id));

        var otherOperation = new Command(KEY, "resumeCampaign", id, "");
        assertThatThrownBy(() -> once.handle(otherOperation, () -> resume.handle(id)))
                .isInstanceOf(CampaignIdempotencyConflictException.class);
        var elsewhere = new Command(KEY, "pauseCampaign", otherCampaign, "");
        assertThatThrownBy(() -> once.handle(elsewhere, () -> pause.handle(otherCampaign)))
                .isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void aKeyThatCreatedACampaignConflictsWithACommand() {
        var id = running();
        var created = UUID.randomUUID();
        campaigns.create(created, "h".repeat(64), Campaign.queued(UUID.randomUUID(), definition(), 4, NOW), NOW);

        var command = new Command(created, "pauseCampaign", id, "");
        assertThatThrownBy(() -> once.handle(command, () -> pause.handle(id)))
                .isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void aRefusedCommandClaimsNothingSoItCanBeAskedAgainWithTheSameKey() {
        var id = searched();
        play.fidelity(false);
        var command = new Command(KEY, "startConfirmation", id, "");

        assertThatThrownBy(() -> once.handle(command, () -> confirm.handle(id)))
                .isInstanceOf(FidelityNotEstablishedException.class);
        assertThat(campaigns.findCommand(KEY)).isEmpty();

        play.fidelity(true);

        assertThat(once.handle(command, () -> confirm.handle(id)).state()).isEqualTo(CampaignState.CONFIRMING);
    }

    private CampaignState state(UUID id) {
        return campaigns.find(id).orElseThrow().campaign().state();
    }

    private UUID running() {
        var queued = Campaign.queued(UUID.randomUUID(), definition(), 4, NOW);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), queued, NOW);
        var stored = campaigns.find(queued.campaignId()).orElseThrow();
        campaigns.apply(CampaignChange.of(stored, queued.start(NOW), NOW));
        return queued.campaignId();
    }

    /** A campaign whose search ended with nothing short-listed, which is all a repeated key needs. */
    private UUID searched() {
        var id = running();
        var stored = campaigns.find(id).orElseThrow();
        campaigns.apply(CampaignChange.of(stored, stored.campaign().completeSearch(List.of()), NOW));
        return id;
    }

    private static CampaignDefinition definition() {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        return new CampaignDefinition(
                "search",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(mapper.createObjectNode().put("id", "random")),
                List.of(3),
                3600,
                "7",
                "9",
                new SearchAlgorithm(8, 2, 2, 2));
    }
}
