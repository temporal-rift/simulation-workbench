package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the generated clients over Spring's {@link RestClient} with bounded connect and read times.
 * Requests omit absent fields entirely: the contracts say optional and mutually exclusive target fields
 * "must be omitted", which an explicit {@code null} would violate.
 */
public class RestApiClients implements ApiClients {

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter(JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                    .withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build());

    public RestApiClients(Duration connectTimeout, Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public <T> T create(Class<T> api, String baseUrl, String bearerToken) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        var client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters.withJsonConverter(json))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(api);
    }
}
