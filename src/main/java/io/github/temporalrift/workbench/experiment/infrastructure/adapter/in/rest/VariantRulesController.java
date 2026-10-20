package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.VariantRulesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRules;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.VariantRulesPreview;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.VariantRulesPreviewRequest;

@RestController
class VariantRulesController implements VariantRulesApi {

    private final PreviewVariantRulesUseCase previewVariantRulesUseCase;
    private final GetVariantRulesUseCase getVariantRulesUseCase;

    VariantRulesController(
            PreviewVariantRulesUseCase previewVariantRulesUseCase, GetVariantRulesUseCase getVariantRulesUseCase) {
        this.previewVariantRulesUseCase = previewVariantRulesUseCase;
        this.getVariantRulesUseCase = getVariantRulesUseCase;
    }

    @Override
    public ResponseEntity<VariantRulesPreview> previewVariantRules(VariantRulesPreviewRequest request) {
        var result = previewVariantRulesUseCase.handle(new PreviewVariantRulesUseCase.Query(
                request.getRulesArtifact().getDigest(),
                request.getParameterSpaceVersion(),
                VariantRulesMapper.overrides(request.getOverrides())));
        return ResponseEntity.ok(VariantRulesMapper.preview(result));
    }

    @Override
    public ResponseEntity<EffectiveRules> getVariantRules(UUID experimentId, String label) {
        return ResponseEntity.ok(VariantRulesMapper.rules(getVariantRulesUseCase.handle(experimentId, label)));
    }
}
