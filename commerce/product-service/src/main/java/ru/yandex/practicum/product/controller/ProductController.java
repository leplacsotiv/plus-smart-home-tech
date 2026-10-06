package ru.yandex.practicum.product.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.yandex.practicum.product.dto.CreateProductRequest;
import ru.yandex.practicum.product.dto.ProductDto;
import ru.yandex.practicum.product.dto.UpdateProductRequest;
import ru.yandex.practicum.product.service.ProductService;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductDto> findAll() {
        return service.findActive();
    }

    @GetMapping("/{id}")
    public ProductDto findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping("/category/{categoryId}")
    public List<ProductDto> findByCategory(@PathVariable Long categoryId) {
        return service.findByCategory(categoryId);
    }

    @GetMapping("/search")
    public List<ProductDto> search(@RequestParam String query) {
        return service.search(query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(@Valid @RequestBody CreateProductRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
        return service.update(id, request);
    }
}
