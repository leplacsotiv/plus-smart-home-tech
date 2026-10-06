package ru.yandex.practicum.inventory.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.inventory.dto.InventoryDto;
import ru.yandex.practicum.inventory.dto.ReserveRequest;
import ru.yandex.practicum.inventory.dto.ReserveResponse;
import ru.yandex.practicum.inventory.dto.UpdateInventoryRequest;
import ru.yandex.practicum.inventory.entity.Inventory;
import ru.yandex.practicum.inventory.exception.ConflictException;
import ru.yandex.practicum.inventory.exception.InsufficientStockException;
import ru.yandex.practicum.inventory.exception.NotFoundException;
import ru.yandex.practicum.inventory.repository.InventoryRepository;

import java.util.List;

@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<InventoryDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public InventoryDto findByProductId(Long productId) {
        return toDto(getEntity(productId));
    }

    @Transactional
    public InventoryDto create(UpdateInventoryRequest request) {
        if (repository.existsByProductId(request.productId())) {
            throw new ConflictException("Складская запись для товара " + request.productId() + " уже существует");
        }
        Inventory inventory = repository.saveAndFlush(new Inventory(request.productId(), request.quantity()));
        return toDto(inventory);
    }

    @Transactional
    public InventoryDto update(UpdateInventoryRequest request) {
        Inventory inventory = getEntity(request.productId());
        if (request.quantity() < inventory.getReservedQuantity()) {
            throw new ConflictException("Общее количество не может быть меньше уже зарезервированного");
        }
        inventory.setQuantity(request.quantity());
        repository.flush();
        return toDto(inventory);
    }

    @Transactional
    public ReserveResponse reserve(ReserveRequest request) {
        Inventory inventory = getEntity(request.productId());
        if (inventory.getAvailableQuantity() < request.quantity()) {
            throw new InsufficientStockException("Недостаточно доступного товара " + request.productId());
        }
        inventory.reserve(request.quantity());
        repository.flush();
        return new ReserveResponse(true, inventory.getAvailableQuantity(), "Товар успешно зарезервирован");
    }

    private Inventory getEntity(Long productId) {
        return repository.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("Складская запись для товара " + productId + " не найдена"));
    }

    private InventoryDto toDto(Inventory inventory) {
        return new InventoryDto(inventory.getId(), inventory.getProductId(), inventory.getQuantity(),
                inventory.getReservedQuantity(), inventory.getAvailableQuantity());
    }
}
