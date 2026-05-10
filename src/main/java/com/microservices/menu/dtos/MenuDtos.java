package com.microservices.menu.dtos;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class MenuDtos {

    public record CreateMenuItemRequest(
            @NotBlank(message = "Name must not be blank")
            String name,
            String description,
            @NotBlank(message = "Category ID must not be blank")
            String categoryId,
            @NotNull(message = "Base price must not be null")
            @DecimalMin(value = "0.00", message = "Base price must be zero or greater")
            BigDecimal basePrice,
            String imageUrl
    ) {}

    public record UpdateMenuItemRequest(
            String name,
            String description,
            String categoryId,
            @DecimalMin(value = "0.00", message = "Base price must be zero or greater")
            BigDecimal basePrice,
            String imageUrl
    ) {}

    public record MenuItemResponse(
            String id,
            String name,
            String description,
            String categoryId,
            String categoryName,
            BigDecimal basePrice,
            String imageUrl,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record MenuItemSummary(
            String id,
            String name,
            String categoryId,
            String categoryName,
            BigDecimal basePrice,
            boolean active
    ) {}

    public record CreateCategoryRequest(
            @NotBlank(message = "Name must not be blank")
            String name,
            int displayOrder
    ) {}

    public record UpdateCategoryRequest(
            String name,
            Integer displayOrder
    ) {}

    public record CategoryResponse(
            String id,
            String name,
            int displayOrder,
            boolean active
    ) {}

    public record FrontendMenuItemResponse(
            String id,
            String name,
            String description,
            double price,
            String category,
            boolean available,
            boolean isActive,
            String imageUrl,
            String image
    ) {}

    public record MenuItemEventPayload(
            String menuItemId,
            String name,
            BigDecimal basePrice,
            boolean active,
            String eventType
    ) {}
}
