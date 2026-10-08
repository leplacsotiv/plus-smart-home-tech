package ru.yandex.practicum.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.product.entity.Product;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByActiveTrueOrderById();

    List<Product> findAllByCategoryIdAndActiveTrueOrderById(Long categoryId);

    List<Product> findAllByNameContainingIgnoreCaseAndActiveTrueOrderById(String query);
}
