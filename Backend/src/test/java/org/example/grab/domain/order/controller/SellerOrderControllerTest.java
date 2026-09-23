package org.example.grab.domain.order.controller;

import org.example.grab.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerOrderControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SellerOrderController(null, null))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void rejectsInvalidStatusParameter() throws Exception {
        // given
        var request = get("/api/v1/seller/orders").param("orderStatus", "NOT_A_STATUS");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsInvalidPageParameters() throws Exception {
        // given
        var request = get("/api/v1/seller/orders").param("page", "-1");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForInvalidOrderId() throws Exception {
        // given
        var request = get("/api/v1/seller/orders/not-a-uuid");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
