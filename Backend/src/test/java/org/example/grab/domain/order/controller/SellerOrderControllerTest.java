package org.example.grab.domain.order.controller;

import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerOrderControllerTest {

    private final SellerOrderService sellerOrderService = mock(SellerOrderService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerOrderController(sellerOrderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

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
        verifyNoInteractions(sellerOrderService);
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
        verifyNoInteractions(sellerOrderService);
    }
}
