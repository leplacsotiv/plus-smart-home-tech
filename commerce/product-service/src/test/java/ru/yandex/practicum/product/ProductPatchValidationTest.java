package ru.yandex.practicum.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.yandex.practicum.product.dto.CreateProductRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ProductPatchValidationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void shouldValidateOnlyNonNullPatchName() throws Exception {
        Long productId = createProduct();

        MvcResult omittedName = patchProduct(productId, "{\"price\":\"120.00\"}");
        assertThat(omittedName.getResponse().getStatus()).isEqualTo(200);
        assertThat(readMap(omittedName).get("name")).isEqualTo("Patch validation product");

        MvcResult nullName = patchProduct(productId, "{\"name\":null,\"price\":\"130.00\"}");
        assertThat(nullName.getResponse().getStatus()).isEqualTo(200);
        assertThat(readMap(nullName).get("name")).isEqualTo("Patch validation product");

        MvcResult emptyName = patchProduct(productId, "{\"name\":\"\"}");
        assertInvalidName(emptyName);

        MvcResult blankName = patchProduct(productId, "{\"name\":\"   \"}");
        assertInvalidName(blankName);

        MvcResult validName = patchProduct(productId, "{\"name\":\"Updated product\"}");
        assertThat(validName.getResponse().getStatus()).isEqualTo(200);
        assertThat(readMap(validName).get("name")).isEqualTo("Updated product");
    }

    private Long createProduct() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                "Patch validation product", null, new BigDecimal("100.00"), null, null
        );
        MvcResult response = mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        return ((Number) readMap(response).get("id")).longValue();
    }

    private MvcResult patchProduct(Long productId, String body) throws Exception {
        return mvc.perform(patch("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private void assertInvalidName(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        Map<String, String> validationErrors = (Map<String, String>) readMap(response).get("validationErrors");
        assertThat(validationErrors).containsEntry("name", "Название товара не может быть пустым");
    }

    private Map<String, Object> readMap(MvcResult response) throws Exception {
        return json.readValue(response.getResponse().getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {
        });
    }
}
