package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;

class ExperimentExceptionHandlerTest {

    private final ExperimentExceptionHandler handler = new ExperimentExceptionHandler();

    @ParameterizedTest
    @CsvSource({
        "INVALID_EXPERIMENT,400",
        "MANIFEST_MISMATCH,409",
        "EXPERIMENT_IMMUTABLE,409",
        "IDEMPOTENCY_CONFLICT,409"
    })
    void aValidationRefusalKeepsItsCodeAndNamesItsValue(ExperimentErrorCode code, int status) {
        var problem = handler.handleValidation(new ExperimentValidationException(code, "no", "seeds[1]"));

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getProperties()).containsEntry("code", code.name()).containsEntry("field", "seeds[1]");
    }

    @Test
    void aRefusalThatIsAboutNoSingleValueCarriesNoField() {
        var problem = handler.handleValidation(
                new ExperimentValidationException(ExperimentErrorCode.INVALID_EXPERIMENT, "too many cases"));

        assertThat(problem.getProperties())
                .containsEntry("code", "INVALID_EXPERIMENT")
                .doesNotContainKey("field");
    }

    @Test
    void otherRefusalsKeepTheirStatusAndCode() {
        assertThat(handler.handleIdempotencyConflict(new IdempotencyConflictException("key"))
                        .getProperties())
                .containsEntry("code", "IDEMPOTENCY_CONFLICT");
        var missing = handler.handleNotFound(new ExperimentNotFoundException(UUID.randomUUID()));
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(missing.getProperties()).containsEntry("code", "RESOURCE_NOT_FOUND");
        assertThat(handler.handleMissingKey(new IllegalArgumentException("Idempotency-Key is required"))
                        .getProperties())
                .containsEntry("code", "INVALID_EXPERIMENT");
    }
}
