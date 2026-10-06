package ru.yandex.practicum.product.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.product.dto.CategoryDto;
import ru.yandex.practicum.product.dto.CreateProductRequest;
import ru.yandex.practicum.product.dto.ProductDto;
import ru.yandex.practicum.product.dto.UpdateProductRequest;
import ru.yandex.practicum.product.entity.Category;
import ru.yandex.practicum.product.entity.Product;
import ru.yandex.practicum.product.exception.NotFoundException;
import ru.yandex.practicum.product.repository.ProductRepository;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository repository;
    private final CategoryService categories;

    public ProductService(ProductRepository repository, CategoryService categories) {
        this.repository = repository;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<ProductDto> findActive() {
        return toDto(repository.findAllByActiveTrueOrderById());
    }

    @Transactional(readOnly = true)
    public ProductDto findById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<ProductDto> findByCategory(Long categoryId) {
        categories.getEntity(categoryId);
        return toDto(repository.findAllByCategoryIdAndActiveTrueOrderById(categoryId));
    }

    @Transactional(readOnly = true)
    public List<ProductDto> search(String query) {
        if (query == null) {
            throw new IllegalArgumentException("Поисковый запрос обязателен");
        }
        return toDto(repository.findAllByNameContainingIgnoreCaseAndActiveTrueOrderById(query.trim()));
    }

    @Transactional
    public ProductDto create(CreateProductRequest request) {
        Category category = request.categoryId() == null ? null : categories.getEntity(request.categoryId());
        Product product = new Product(request.name(), request.description(), request.price(), category, request.imageUrl());
        return toDto(repository.save(product));
    }

    @Transactional
    public ProductDto update(Long id, UpdateProductRequest request) {
        Product product = getEntity(id);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new IllegalArgumentException("Название товара не может быть пустым");
            }
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.categoryId() != null) {
            product.setCategory(categories.getEntity(request.categoryId()));
        }
        if (request.imageUrl() != null) {
            product.setImageUrl(request.imageUrl());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        return toDto(product);
    }

    private Product getEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Товар с id=" + id + " не найден"));
    }

    private List<ProductDto> toDto(List<Product> products) {
        return products.stream().map(this::toDto).toList();
    }

    private ProductDto toDto(Product product) {
        Category category = product.getCategory();
        CategoryDto categoryDto = category == null ? null : categories.toDto(category);
        return new ProductDto(product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                categoryDto, product.getImageUrl(), product.isActive());
    }
}
