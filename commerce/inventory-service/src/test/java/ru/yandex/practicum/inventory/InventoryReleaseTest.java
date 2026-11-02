package ru.yandex.practicum.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InventoryReleaseTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void releasesOnlyPreviouslyReservedStock() throws Exception {
        long productId = 910001L;

        postQuantity("/api/inventory", productId, 10)
                .andExpect(status().isCreated());

        postQuantity("/api/inventory/reserve", productId, 4)
                .andExpect(status().isOk());

        postQuantity("/api/inventory/release", productId, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.availableQuantity").value(8));

        mvc.perform(get("/api/inventory/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedQuantity").value(2));
    }

    @Test
    void rejectsReleaseLargerThanReservationWithoutChangingStock() throws Exception {
        long productId = 910002L;

        postQuantity("/api/inventory", productId, 5)
                .andExpect(status().isCreated());

        postQuantity("/api/inventory/reserve", productId, 2)
                .andExpect(status().isOk());

        postQuantity("/api/inventory/release", productId, 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(get("/api/inventory/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedQuantity").value(2));
    }

    @Test
    void returns404ForMissingItemAnd400ForInvalidQuantity() throws Exception {
        postQuantity("/api/inventory/release", 910003L, 1)
                .andExpect(status().isNotFound());

        postQuantity("/api/inventory/release", 910003L, 0)
                .andExpect(status().isBadRequest());
    }

    private ResultActions postQuantity(
            String url,
            long productId,
            int quantity
    ) throws Exception {
        String json =
                "{\"productId\":%d,\"quantity\":%d}"
                        .formatted(productId, quantity);

        return mvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
