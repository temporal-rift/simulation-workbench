package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.search.application.port.in.CampaignCommandOnceUseCase;
import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignLogUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ListCampaignsUseCase;
import io.github.temporalrift.workbench.search.application.port.in.StartConfirmationUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.CampaignsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Campaign;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignList;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogPage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignParallelismUpdate;

@RestController
class CampaignController implements CampaignsApi {

    private final CreateCampaignUseCase createCampaign;
    private final GetCampaignUseCase getCampaign;
    private final ListCampaignsUseCase listCampaigns;
    private final GetCampaignLogUseCase getLog;
    private final StartConfirmationUseCase startConfirmation;
    private final CampaignControls controls;
    private final CampaignApiMapper mapper;

    CampaignController(
            CreateCampaignUseCase createCampaign,
            GetCampaignUseCase getCampaign,
            ListCampaignsUseCase listCampaigns,
            GetCampaignLogUseCase getLog,
            StartConfirmationUseCase startConfirmation,
            CampaignControls controls,
            ObjectMapper objectMapper) {
        this.createCampaign = createCampaign;
        this.getCampaign = getCampaign;
        this.listCampaigns = listCampaigns;
        this.getLog = getLog;
        this.startConfirmation = startConfirmation;
        this.controls = controls;
        this.mapper = new CampaignApiMapper(objectMapper);
    }

    @Override
    public ResponseEntity<Campaign> createCampaign(UUID idempotencyKey, CampaignDefinition definition) {
        var campaign = createCampaign.handle(mapper.toCommand(idempotencyKey, definition));
        return ResponseEntity.status(201).body(mapper.toApi(campaign));
    }

    @Override
    public ResponseEntity<CampaignList> listCampaigns(Integer limit, Integer offset) {
        return ResponseEntity.ok(mapper.toApi(listCampaigns.handle(new ListCampaignsUseCase.Query(limit, offset))));
    }

    @Override
    public ResponseEntity<Campaign> getCampaign(UUID campaignId) {
        return ResponseEntity.ok(mapper.toApi(getCampaign.handle(campaignId)));
    }

    @Override
    public ResponseEntity<CampaignLogPage> getCampaignLog(UUID campaignId, Integer limit, Integer offset) {
        return ResponseEntity.ok(
                mapper.toApiLog(getLog.handle(new GetCampaignLogUseCase.Query(campaignId, limit, offset))));
    }

    @Override
    public ResponseEntity<Campaign> pauseCampaign(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.ok(once(
                idempotencyKey,
                "pauseCampaign",
                campaignId,
                "",
                () -> controls.pause().handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> resumeCampaign(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.ok(once(
                idempotencyKey,
                "resumeCampaign",
                campaignId,
                "",
                () -> controls.resume().handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> cancelCampaign(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.ok(once(
                idempotencyKey,
                "cancelCampaign",
                campaignId,
                "",
                () -> controls.cancel().handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> setCampaignParallelism(
            UUID campaignId, UUID idempotencyKey, CampaignParallelismUpdate update) {
        var parallelism = update.getParallelism();
        return ResponseEntity.ok(once(
                idempotencyKey,
                "setCampaignParallelism",
                campaignId,
                String.valueOf(parallelism),
                () -> controls.parallelism().handle(campaignId, parallelism)));
    }

    @Override
    public ResponseEntity<Campaign> startConfirmation(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.accepted()
                .body(once(
                        idempotencyKey,
                        "startConfirmation",
                        campaignId,
                        "",
                        () -> startConfirmation.handle(campaignId)));
    }

    private Campaign once(
            UUID idempotencyKey,
            String operation,
            UUID campaignId,
            String body,
            java.util.function.Supplier<io.github.temporalrift.workbench.search.domain.campaign.Campaign> command) {
        return mapper.toApi(controls.once()
                .handle(new CampaignCommandOnceUseCase.Command(idempotencyKey, operation, campaignId, body), command));
    }
}
