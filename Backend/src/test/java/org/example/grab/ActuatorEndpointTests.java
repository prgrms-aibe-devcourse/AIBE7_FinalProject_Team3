package org.example.grab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMetrics
@AutoConfigureMockMvc
class ActuatorEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesPrometheusMetricsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")));
    }

    @Test
    void keepsOtherRoutesProtected() throws Exception {
        mockMvc.perform(get("/").header("Accept", "application/json"))
                .andExpect(status().isUnauthorized());
    }
}
