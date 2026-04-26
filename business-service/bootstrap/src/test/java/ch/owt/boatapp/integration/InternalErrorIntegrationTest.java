package ch.owt.boatapp.integration;

import ch.owt.boatapp.application.service.BoatApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import static ch.owt.boatapp.testsupport.JwtTestSupport.mockJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the 500 fallback path of {@code GlobalExceptionHandler}. Lives in
 * its own class because the {@link MockitoBean} on
 * {@link BoatApplicationService} would break every test that needs a real
 * service (CRUD, optimistic locking, etc.). The mock is configured so that
 * any call to {@code listBoats} throws, which exercises the catch-all
 * {@code @ExceptionHandler(Exception.class)}.
 */
class InternalErrorIntegrationTest extends IntegrationTestBase {

    private static final String INTERNAL_TYPE = "https://boatapp.owt.ch/problems/internal";

    @MockitoBean
    private BoatApplicationService boatApplicationService;

    /**
     * GET when the application service throws → 500 with type
     * {@code .../internal} and no leaked stack-trace text in the body.
     */
    @Test
    void unhandledException_returns500_withInternalType_andNoLeakedStack() throws Exception {
        when(boatApplicationService.listBoats(any()))
                .thenThrow(new RuntimeException("synthetic failure for test"));

        MvcResult res = mockMvc.perform(get("/api/v1/boats").with(mockJwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(INTERNAL_TYPE))
                .andExpect(jsonPath("$.status").value(500))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("500 body must not leak a stack trace")
                .doesNotContain("synthetic failure for test")
                .doesNotContain("RuntimeException")
                .doesNotContain("at ch.owt.boatapp")
                .doesNotContain("about:blank");
    }
}
