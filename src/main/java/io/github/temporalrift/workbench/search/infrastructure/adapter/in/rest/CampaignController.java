package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.search.application.port.in.CancelCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignLogUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ListCampaignsUseCase;
import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.ConfirmationNotAvailableException;
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
    private final PauseCampaignUseCase pauseCampaign;
    private final ResumeCampaignUseCase resumeCampaign;
    private final CancelCampaignUseCase cancelCampaign;
    private final SetCampaignParallelismUseCase setParallelism;
    private final CampaignApiMapper mapper;

    CampaignController(
            CreateCampaignUseCase createCampaign,
            GetCampaignUseCase getCampaign,
            ListCampaignsUseCase listCampaigns,
            GetCampaignLogUseCase getLog,
            PauseCampaignUseCase pauseCampaign,
            ResumeCampaignUseCase resumeCampaign,
            CancelCampaignUseCase cancelCampaign,
            SetCampaignParallelismUseCase setParallelism,
            ObjectMapper objectMapper) {
        this.createCampaign = createCampaign;
        this.getCampaign = getCampaign;
        this.listCampaigns = listCampaigns;
        this.getLog = getLog;
        this.pauseCampaign = pauseCampaign;
        this.resumeCampaign = resumeCampaign;
        this.cancelCampaign = cancelCampaign;
        this.setParallelism = setParallelism;
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
        return ResponseEntity.ok(mapper.toApi(pauseCampaign.handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> resumeCampaign(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.ok(mapper.toApi(resumeCampaign.handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> cancelCampaign(UUID campaignId, UUID idempotencyKey) {
        return ResponseEntity.ok(mapper.toApi(cancelCampaign.handle(campaignId)));
    }

    @Override
    public ResponseEntity<Campaign> setCampaignParallelism(
            UUID campaignId, UUID idempotencyKey, CampaignParallelismUpdate update) {
        return ResponseEntity.ok(mapper.toApi(setParallelism.handle(campaignId, update.getParallelism())));
    }

    @Override
    public ResponseEntity<Campaign> startConfirmation(UUID campaignId, UUID idempotencyKey) {
        throw new ConfirmationNotAvailableException();
    }
}
