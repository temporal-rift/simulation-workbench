package io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignDefaults;

/**
 * The defaults of a campaign as the service ships them: the search parameters of the newest
 * {@code search-budgets/v*.json} record on the classpath, and the newest parameter space and balance criterion the
 * other modules serve. A record is added by adding a file; nothing creates, edits or deletes one at run time.
 */
public class ShippedCampaignDefaults implements CampaignDefaults {

    private static final String LOCATION = "classpath*:search-budgets/v*.json";

    private final ExperimentFactory experiments;
    private final CandidateScreening screening;
    private final SearchAlgorithm algorithm;

    public ShippedCampaignDefaults(
            ExperimentFactory experiments, CandidateScreening screening, ObjectMapper objectMapper) {
        this.experiments = experiments;
        this.screening = screening;
        this.algorithm = newestAlgorithm(objectMapper);
    }

    @Override
    public SearchAlgorithm algorithm() {
        return algorithm;
    }

    @Override
    public int parameterSpaceVersion() {
        return experiments.newestParameterSpaceVersion();
    }

    @Override
    public int criterionVersion() {
        return screening.newestCriterionVersion();
    }

    private static SearchAlgorithm newestAlgorithm(ObjectMapper objectMapper) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        } catch (IOException e) {
            throw new UncheckedIOException("The search budget records could not be listed", e);
        }
        var records = Arrays.stream(resources)
                .map(resource -> read(objectMapper, resource))
                .sorted(Comparator.comparingInt(BudgetRecord::version))
                .toList();
        if (records.isEmpty()) {
            throw new IllegalStateException("No search budget record is configured (" + LOCATION + ")");
        }
        if (records.stream().map(BudgetRecord::version).distinct().count() != records.size()) {
            throw new IllegalStateException("A search budget record version is configured twice");
        }
        return records.getLast().algorithm();
    }

    private static BudgetRecord read(ObjectMapper objectMapper, Resource resource) {
        var name = resource.getFilename();
        try (var in = resource.getInputStream()) {
            JsonNode document = objectMapper.readTree(in);
            var algorithm = document.path("algorithm");
            if (!document.path("version").isInt()) {
                throw new IllegalStateException("Search budget record " + name + " has no integer version");
            }
            if (!algorithm.path("version").asString().equals("SUCCESSIVE_HALVING_V1")) {
                throw new IllegalStateException("Search budget record " + name + " names an unknown algorithm");
            }
            return new BudgetRecord(
                    document.path("version").asInt(),
                    new SearchAlgorithm(
                            integer(algorithm, "initialCandidates", name),
                            integer(algorithm, "screeningSeeds", name),
                            integer(algorithm, "eta", name),
                            integer(algorithm, "shortListSize", name)));
        } catch (IOException e) {
            throw new UncheckedIOException("Search budget record " + name + " could not be read", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Search budget record " + name + " is invalid: " + e.getMessage(), e);
        }
    }

    private static int integer(JsonNode algorithm, String key, String name) {
        if (!algorithm.path(key).isInt()) {
            throw new IllegalStateException("Search budget record " + name + " has no integer " + key);
        }
        return algorithm.path(key).asInt();
    }

    private record BudgetRecord(int version, SearchAlgorithm algorithm) {}
}
