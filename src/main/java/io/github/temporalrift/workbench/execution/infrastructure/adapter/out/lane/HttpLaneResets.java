package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls each lane's reset hook: {@code POST} with the lane's operator token and an idempotency key, answered with a
 * {@code 2xx} once the deployment made the lane clean. Any other answer, a connection failure or a
 * {@link ResetTimeoutException} throws.
 */
public class HttpLaneResets implements LaneResets {

    private final Map<String, LaneEndpoints> lanes;
    private final RestClient client;

    public HttpLaneResets(List<LaneEndpoints> lanes, Duration connectTimeout, Duration resetTimeout) {
        this.lanes = lanes.stream()
                .filter(lane -> lane.resetUrl() != null)
                .collect(Collectors.toUnmodifiableMap(LaneEndpoints::id, Function.identity()));
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(resetTimeout);
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (_, response) -> {
                    throw new IllegalStateException(
                            "status " + response.getStatusCode().value());
                })
                .build();
    }

    @Override
    public void reset(String laneId, UUID caseKey, UUID idempotencyKey) {
        var lane = lanes.get(laneId);
        if (lane == null) {
            throw new IllegalArgumentException("Lane " + laneId + " has no reset hook");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("laneId", laneId);
        if (caseKey != null) {
            body.put("caseKey", caseKey);
        }
        try {
            client.post()
                    .uri(lane.resetUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + lane.operatorToken())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw isTimeout(e) ? new ResetTimeoutException(laneId, e) : e;
        }
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
