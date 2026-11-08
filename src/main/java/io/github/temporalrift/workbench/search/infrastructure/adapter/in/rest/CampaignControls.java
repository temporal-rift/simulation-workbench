package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import org.springframework.stereotype.Component;

import io.github.temporalrift.workbench.search.application.port.in.CancelCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;

/** The commands that steer a running campaign, which the campaign endpoints use together. */
@Component
record CampaignControls(
        PauseCampaignUseCase pause,
        ResumeCampaignUseCase resume,
        CancelCampaignUseCase cancel,
        SetCampaignParallelismUseCase parallelism) {}
