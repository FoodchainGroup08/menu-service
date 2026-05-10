package com.microservices.menu.controller;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/menu/items")
@Tag(name = "Menu Items", description = "Browse and manage menu items. Through the API gateway, reads require a JWT; admin writes require HEAD_OFFICE_ADMIN or OFFICE_ADMIN (see X-User-Role when calling services directly).")
public class MenuItemController {

    @Autowired
    private MenuService menuService;

    // ── GET /menu/items ───────────────────────────────────────────────────────

    @Operation(
        summary = "List menu items",
        description = "Returns a paginated list of menu items. Optionally filter by category or active status. Results are sorted alphabetically by name.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of menu item summaries")
    })
    @GetMapping
    public ResponseEntity<Page<MenuDtos.MenuItemSummary>> listItems(
            @Parameter(description = "Filter by category UUID — returns only items belonging to this category")
            @RequestParam(required = false)    String  categoryId,
            @Parameter(description = "Filter by active status — true returns available items, false returns hidden items")
            @RequestParam(required = false)    Boolean active,
            @Parameter(description = "Zero-based page number (default 0)")
            @RequestParam(defaultValue = "0")  int     page,
            @Parameter(description = "Number of items per page (default 20)")
            @RequestParam(defaultValue = "20") int     size) {
        return ResponseEntity.ok(menuService.listMenuItems(categoryId, active,
                PageRequest.of(page, size, Sort.by("name").ascending())));
    }

    // ── GET /menu/items/{id} ──────────────────────────────────────────────────

    @Operation(
        summary = "Get menu item by ID",
        description = "Returns full details for a single menu item. Response is served from Redis cache (TTL 10 min) when available.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Menu item details"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<MenuDtos.MenuItemResponse> getItem(
            @Parameter(description = "UUID of the menu item", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(menuService.getMenuItem(id));
    }

    // ── POST /menu/items ──────────────────────────────────────────────────────

    @Operation(
        summary = "Create a menu item",
        description = "Creates a new menu item and publishes a CREATED event to Kafka. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Item created successfully"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Supplied categoryId does not exist")
    })
    @PostMapping
    public ResponseEntity<MenuDtos.MenuItemResponse> createItem(
            @Valid @RequestBody  MenuDtos.CreateMenuItemRequest request,
            @Parameter(description = "Caller's user UUID — injected by the API Gateway from the JWT. When testing directly on Swagger, paste your user UUID here.", example = "00000000-0000-0000-0000-000000000001") @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        log.info("POST /menu/items userId={} role={}", userId, userRole);
        assertAdmin(userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.createMenuItem(request));
    }

    // ── PUT /menu/items/{id} ──────────────────────────────────────────────────

    @Operation(
        summary = "Update a menu item",
        description = "Performs a partial update on the specified item — only fields present in the request body are changed. Evicts the Redis cache entry and publishes an UPDATED event to Kafka. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item updated successfully"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @PutMapping("/{id}")
    public ResponseEntity<MenuDtos.MenuItemResponse> updateItem(
            @Parameter(description = "UUID of the menu item to update", required = true)
            @PathVariable String id,
            @Valid @RequestBody  MenuDtos.UpdateMenuItemRequest request,
            @Parameter(description = "Caller's user UUID — injected by the API Gateway from the JWT. When testing directly on Swagger, paste your user UUID here.", example = "00000000-0000-0000-0000-000000000001") @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        log.info("PUT /menu/items/{} userId={} role={}", id, userId, userRole);
        assertAdmin(userRole);
        return ResponseEntity.ok(menuService.updateMenuItem(id, request));
    }

    // ── PATCH /menu/items/{id}/activate ──────────────────────────────────────

    @Operation(
        summary = "Activate a menu item",
        description = "Sets the item's active flag to true, making it visible to customers. Publishes an ACTIVATED event to Kafka. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item activated"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @PatchMapping("/{id}/activate")
    public ResponseEntity<MenuDtos.MenuItemResponse> activate(
            @Parameter(description = "UUID of the menu item", required = true)
            @PathVariable String id,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        assertAdmin(userRole);
        return ResponseEntity.ok(menuService.setItemActive(id, true));
    }

    // ── PATCH /menu/items/{id}/deactivate ─────────────────────────────────────

    @Operation(
        summary = "Deactivate a menu item",
        description = "Sets the item's active flag to false, hiding it from customers without deleting it. Publishes a DEACTIVATED event to Kafka. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item deactivated"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<MenuDtos.MenuItemResponse> deactivate(
            @Parameter(description = "UUID of the menu item", required = true)
            @PathVariable String id,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        assertAdmin(userRole);
        return ResponseEntity.ok(menuService.setItemActive(id, false));
    }

    // ── PATCH /menu/items/{id}/toggle ─────────────────────────────────────────

    @Operation(
        summary = "Toggle a menu item's active state",
        description = "Flips the active flag: active items become inactive and vice versa. Publishes ACTIVATED or DEACTIVATED event to Kafka accordingly. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item state toggled"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<MenuDtos.MenuItemResponse> toggle(
            @Parameter(description = "UUID of the menu item", required = true)
            @PathVariable String id,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        assertAdmin(userRole);
        return ResponseEntity.ok(menuService.toggleItemActive(id));
    }

    // ── DELETE /menu/items/{id} ───────────────────────────────────────────────

    @Operation(
        summary = "Delete a menu item",
        description = "Permanently removes the menu item from the database. Evicts the Redis cache entry and publishes a DELETED event to Kafka. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item deleted successfully"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Item not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteItem(
            @Parameter(description = "UUID of the menu item to delete", required = true)
            @PathVariable String id,
            @Parameter(description = "Caller's user UUID — injected by the API Gateway from the JWT. When testing directly on Swagger, paste your user UUID here.", example = "00000000-0000-0000-0000-000000000001") @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "Caller's role — injected by the API Gateway from the JWT. When testing directly on Swagger, enter HEAD_OFFICE_ADMIN for admin access.", example = "HEAD_OFFICE_ADMIN") @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        log.info("DELETE /menu/items/{} userId={} role={}", id, userId, userRole);
        assertAdmin(userRole);
        menuService.deleteMenuItem(id);
        return ResponseEntity.ok(Map.of(
                "message", "Menu item deleted successfully",
                "id", id));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void assertAdmin(String userRole) {
        if (userRole == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authentication required — add X-User-Role header (value: HEAD_OFFICE_ADMIN)");
        }
        if (!"OFFICE_ADMIN".equals(userRole) && !"HEAD_OFFICE_ADMIN".equals(userRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient role to perform this action");
        }
    }
}
