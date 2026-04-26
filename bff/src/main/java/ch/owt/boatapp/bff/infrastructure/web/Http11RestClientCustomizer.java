package ch.owt.boatapp.bff.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Pin the JDK {@link HttpClient} backing every {@link RestClient} in the
 * application to HTTP/1.1 by exposing a single
 * {@link ClientHttpRequestFactory} bean that Spring Cloud Gateway's
 * {@code gatewayRestClientCustomizer} picks up via its
 * {@code ObjectProvider<ClientHttpRequestFactory>} parameter and applies to
 * the {@code RestClient.Builder} used by {@code RestClientProxyExchange}.
 *
 * <p>Why this is needed. Spring Cloud Gateway Server Web MVC's outbound
 * RestClient default — Spring's {@link JdkClientHttpRequestFactory} on Java
 * 25 — negotiates HTTP/2 cleartext (h2c) on plaintext upstreams. When the
 * upstream speaks HTTP/1.1 only — as the WireMock 3.x standalone server
 * does in our integration tests, and as the Business Service does in
 * production over its private Container Apps ingress — h2c negotiation
 * stalls and the JDK client surfaces a {@code Received RST_STREAM: Stream
 * cancelled} error. Forcing HTTP/1.1 here removes that hazard for every
 * outbound BFF call (the production Business Service is HTTP/1.1 over the
 * Container Apps internal mesh, so there is no functional regression).
 *
 * <p>A naive {@code RestClientCustomizer} approach does not work here:
 * Spring Cloud Gateway registers its own customizer
 * ({@code gatewayRestClientCustomizer}) that overrides the request factory
 * unless an explicit {@link ClientHttpRequestFactory} bean is provided —
 * which is exactly what this class does.
 */
@Configuration
public class Http11RestClientCustomizer {

    /**
     * Build the HTTP/1.1-pinned request factory bean SCG's
     * {@code gatewayRestClientCustomizer} consumes. Returning a
     * {@link ClientHttpRequestFactory} (not the more specific subtype) keeps
     * the bean type aligned with the {@code ObjectProvider} parameter
     * Spring Cloud Gateway uses to discover it.
     *
     * @return a {@link JdkClientHttpRequestFactory} backed by a JDK
     *         {@link HttpClient} pinned to {@link HttpClient.Version#HTTP_1_1}
     */
    @Bean
    public ClientHttpRequestFactory gatewayHttp11ClientHttpRequestFactory() {
        HttpClient http11 = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new JdkClientHttpRequestFactory(http11);
    }
}
