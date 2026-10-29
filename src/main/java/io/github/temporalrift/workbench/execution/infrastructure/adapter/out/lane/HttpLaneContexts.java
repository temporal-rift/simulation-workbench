package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;

/**
 * Closes execution contexts with {@code closeSimulationExecution} on the game, timeline and read services of a lane. A
 * {@code 204} and a {@code 409 EXECUTION_NOT_CONFIGURED} both mean the service holds no context of the case. Every
 * service is asked even when another fails, so one failure never leaves a context open that could have been closed.
 */
public class HttpLaneContexts implements LaneContexts {

    private static final String NOT_CONFIGURED = "EXECUTION_NOT_CONFIGURED";
    private static final List<LaneService> CONTROLLED =
            List.of(LaneService.GAME, LaneService.TIMELINE, LaneService.READ);

    private final Map<String, LaneEndpoints> lanes;
    private final ApiClients clients;

    /** @param clients clients bounded by the time one call may take */
    public HttpLaneContexts(List<LaneEndpoints> lanes, ApiClients clients) {
        this.lanes = lanes.stream().collect(Collectors.toUnmodifiableMap(LaneEndpoints::id, Function.identity()));
        this.clients = clients;
    }

    @Override
    public void close(String laneId, UUID caseKey, Set<LaneService> services) {
        eachService(laneId, services, (lane, service) -> close(lane, service, caseKey));
    }

    @Override
    public void closeAnyOpen(String laneId) {
        eachService(laneId, Set.copyOf(CONTROLLED), (lane, service) -> {
            var open = openCase(lane, service);
            if (open != null) {
                close(lane, service, open);
            }
        });
    }

    private interface Step {
        void run(LaneEndpoints lane, LaneService service);
    }

    private void eachService(String laneId, Set<LaneService> services, Step step) {
        var lane = lanes.get(laneId);
        if (lane == null) {
            throw new IllegalArgumentException("Lane " + laneId + " is not configured");
        }
        RuntimeException failure = null;
        for (var service : CONTROLLED) {
            if (!services.contains(service)) {
                continue;
            }
            try {
                step.run(lane, service);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The case whose context the service holds open, or null when it holds none. */
    private UUID openCase(LaneEndpoints lane, LaneService service) {
        try {
            var checkpoint = control(lane, service).getSimulationCheckpoint().getBody();
            return checkpoint == null ? null : checkpoint.getCaseKey();
        } catch (RestClientResponseException e) {
            if (isNotConfigured(e)) {
                return null;
            }
            throw new IllegalStateException(
                    service.serviceName() + " answered the checkpoint with " + ProblemCodes.codeOf(e), e);
        } catch (RestClientException e) {
            throw failed(lane, service, e);
        }
    }

    private void close(LaneEndpoints lane, LaneService service, UUID caseKey) {
        try {
            control(lane, service).closeSimulationExecution(caseKey);
        } catch (RestClientResponseException e) {
            if (!isNotConfigured(e)) {
                throw new IllegalStateException(
                        service.serviceName() + " answered the close with " + ProblemCodes.codeOf(e), e);
            }
        } catch (RestClientException e) {
            throw failed(lane, service, e);
        }
    }

    private SimulationExecutionApi control(LaneEndpoints lane, LaneService service) {
        var url = switch (service) {
            case GAME -> lane.gameServiceUrl();
            case TIMELINE -> lane.timelineServiceUrl();
            case READ -> lane.readServiceUrl();
        };
        return clients.create(SimulationExecutionApi.class, url, lane.operatorToken());
    }

    private static boolean isNotConfigured(RestClientResponseException e) {
        return e.getStatusCode().value() == 409 && NOT_CONFIGURED.equals(ProblemCodes.codeOf(e));
    }

    private static RuntimeException failed(LaneEndpoints lane, LaneService service, RestClientException e) {
        if (e instanceof ResourceAccessException && isTimeout(e)) {
            return new CloseTimeoutException(lane.id(), e);
        }
        return new IllegalStateException(service.serviceName() + " could not be reached: " + e.getMessage(), e);
    }

    private static boolean isTimeout(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
