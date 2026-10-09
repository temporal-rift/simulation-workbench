package io.github.temporalrift.workbench.analysis.infrastructure.config;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.application.command.CreateComparisonCommandHandler;
import io.github.temporalrift.workbench.analysis.application.port.in.CreateComparisonUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.GetComparisonUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.GetRunReportUseCase;
import io.github.temporalrift.workbench.analysis.application.query.CaseFactsProvider;
import io.github.temporalrift.workbench.analysis.application.query.ComparisonEvaluator;
import io.github.temporalrift.workbench.analysis.application.query.GetComparisonQueryHandler;
import io.github.temporalrift.workbench.analysis.application.query.GetRunReportQueryHandler;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest.AnalysisCsvConverter;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.execution.RunSourceAdapter;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.experiment.ExperimentDefinitionsAdapter;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence.CaseFactsRepositoryAdapter;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence.ComparisonRepositoryAdapter;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Cohort;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonCohort;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExcludedPair;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Metric;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PairedMetricDifference;

@Configuration
public class AnalysisConfiguration {

    @Bean
    RunSource analysisRunSource(RunCatalog catalog, ObjectMapper objectMapper) {
        return new RunSourceAdapter(catalog, objectMapper);
    }

    @Bean
    ExperimentDefinitions experimentDefinitions(ExperimentCatalog catalog) {
        return new ExperimentDefinitionsAdapter(catalog);
    }

    @Bean
    CaseFactsRepository caseFactsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        return new CaseFactsRepositoryAdapter(jdbc, objectMapper, clock);
    }

    @Bean
    ComparisonRepository comparisonRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        return new ComparisonRepositoryAdapter(jdbc, objectMapper, clock);
    }

    @Bean
    CaseFactsProvider caseFactsProvider(RunSource runs, CaseFactsRepository facts) {
        return new CaseFactsProvider(runs, facts);
    }

    @Bean
    ComparisonEvaluator comparisonEvaluator(RunSource runs, CaseFactsProvider facts) {
        return new ComparisonEvaluator(runs, facts);
    }

    @Bean
    GetRunReportUseCase getRunReportUseCase(
            RunSource runs, ExperimentDefinitions experiments, CaseFactsProvider facts) {
        return new GetRunReportQueryHandler(runs, experiments, facts);
    }

    @Bean
    CreateComparisonUseCase createComparisonUseCase(
            RunSource runs,
            ExperimentDefinitions experiments,
            ComparisonRepository comparisons,
            ComparisonEvaluator evaluator) {
        return new CreateComparisonCommandHandler(runs, experiments, comparisons, evaluator);
    }

    @Bean
    GetComparisonUseCase getComparisonUseCase(ComparisonRepository comparisons, ComparisonEvaluator evaluator) {
        return new GetComparisonQueryHandler(comparisons, evaluator);
    }

    @Bean
    ServerHttpMessageConvertersCustomizer analysisCsvExports() {
        return builder -> builder.addCustomConverter(new AnalysisCsvConverter());
    }

    /**
     * Report and comparison fields that are required but nullable (a pooled cohort's keys, a metric's value,
     * numerator and interval, an excluded pair's case ids) must survive the service-wide non-null inclusion.
     */
    @Bean
    JsonMapperBuilderCustomizer analysisApiKeepsNullableFields() {
        var always = JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS);
        return builder -> List.of(
                        Cohort.class,
                        Metric.class,
                        ComparisonCohort.class,
                        PairedMetricDifference.class,
                        ExcludedPair.class)
                .forEach(type -> builder.withConfigOverride(type, override -> override.setInclude(always)));
    }
}
