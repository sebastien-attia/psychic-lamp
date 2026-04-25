package ch.owt.boatapp.bff.infrastructure.config;

import ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * BFF infrastructure wiring.
 *
 * <p>Step 02a3 wires the minimum needed for {@code BoatBffService} to invoke
 * the upstream Business Service:
 * <ul>
 *   <li>a {@link RestClient} pointing at the configured
 *       {@code business-service.url};</li>
 *   <li>a {@link BusinessServiceClient} HTTP-Interface proxy built via
 *       {@link HttpServiceProxyFactory}.</li>
 * </ul>
 *
 * <p>Step 02a4 will add:
 * <ul>
 *   <li>the Nimbus {@code RSAKey} bean built from the PEM at
 *       {@code bff.signing-key.path} (used to sign the {@code client_assertion}
 *       JWT for {@code private_key_jwt} and exposed as a {@code JWKSet} by
 *       {@code JwksController});</li>
 *   <li>the OAuth2 {@code Bearer} access-token interceptor on
 *       {@link RestClient}, fed by {@code DefaultOAuth2AuthorizedClientManager}.</li>
 * </ul>
 */
@Configuration
public class BffConfig {

    /**
     * Build the {@link RestClient} used by the {@link BusinessServiceClient}
     * proxy to call the upstream Business Service. The OAuth2 token-forwarding
     * interceptor is attached in step 02a4.
     *
     * @param baseUrl base URL of the upstream Business Service (defaults to
     *                {@code http://localhost:8081} when the property is unset)
     * @return a {@link RestClient} bound to the upstream base URL
     */
    @Bean
    public RestClient businessServiceRestClient(
            @Value("${business-service.url:http://localhost:8081}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Build the OpenAPI-generated {@link BusinessServiceClient} HTTP-Interface
     * proxy backed by the configured {@link RestClient}.
     *
     * @param restClient the BFF's outbound HTTP client
     * @return the proxy implementing {@link BusinessServiceClient}
     */
    @Bean
    public BusinessServiceClient businessServiceClient(RestClient restClient) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(BusinessServiceClient.class);
    }
}
