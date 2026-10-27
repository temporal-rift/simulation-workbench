package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.rest;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityVariantLabel;

/** Reads the {@code variantLabel} filter as the contract spells it: {@code baseline}, {@code low} or {@code high}. */
@Component
class FidelityVariantLabelConverter implements Converter<String, FidelityVariantLabel> {

    @Override
    public FidelityVariantLabel convert(String source) {
        return FidelityVariantLabel.fromValue(source);
    }
}
