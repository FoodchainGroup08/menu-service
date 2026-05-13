package com.microservices.menu.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class MenuDtos {

    public record CreateMenuItemRequest(
            String name,
            String description,
            String categoryId,
            BigDecimal basePrice,
            String imageUrl
    ) {}

    public record UpdateMenuItemRequest(
            String name,
            String description,
            String categoryId,
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

    public record FoodSuggestionRequest(
            String branchId,
            String branchName,
            BigDecimal budget,
            String mealType,
            String appetite,
            List<String> dietaryPreferences,
            Integer peopleCount,
            String fulfillmentType,
            Integer limit
    ) {}

    public record FoodSuggestionResponse(
            String message,
            boolean readyForSuggestions,
            List<String> questions,
            List<FoodSuggestionItem> suggestions,
            BigDecimal estimatedTotalCost
    ) {}

    public record FoodSuggestionItem(
            String menuItemId,
            String menuItemName,
            BigDecimal price,
            String reason,
            String branchId,
            String branchName,
            BigDecimal estimatedTotalCost,
            List<String> optionalAddOns
    ) {}
}
