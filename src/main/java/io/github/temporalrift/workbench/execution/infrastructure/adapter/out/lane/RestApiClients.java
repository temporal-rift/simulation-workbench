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
 * "must be omitted", which an explicit {@code null} would violate. All clients share one connection pool.
 */
public class RestApiClients implements ApiClients {

    private final JdkClientHttpRequestFactory requestFactory;
    private final JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter(JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                    .withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build());

    public RestApiClients(Duration connectTimeout, Duration readTimeout) {
        this.requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        this.requestFactory.setReadTimeout(readTimeout);
    }

    @Override
    public <T> T create(Class<T> api, String baseUrl, String bearerToken) {
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
