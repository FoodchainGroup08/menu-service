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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/v1/menu/categories")
@Tag(name = "Menu Categories", description = "Manage the category groupings that organise menu items (e.g. Starters, Mains, Drinks). Read operations are public; write operations require OFFICE_ADMIN role.")
public class MenuCategoryController {

    @Autowired
    private MenuService menuService;

    // ── GET /menu/categories ──────────────────────────────────────────────────

    @Operation(
        summary = "List all active categories",
        description = "Returns all active menu categories ordered by displayOrder ascending. " +
                      "Add ?namesOnly=true to receive a plain string array of category names (frontend-compatible). " +
                      "Full object responses are served from Redis cache (TTL 10 min) when available.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of active categories in display order")
    })
    @GetMapping
    public ResponseEntity<?> listCategories(
            @Parameter(description = "When true, returns a plain string array of category names instead of full objects")
            @RequestParam(defaultValue = "false") boolean namesOnly) {
        if (namesOnly) {
            return ResponseEntity.ok(menuService.listCategoryNames());
        }
        return ResponseEntity.ok(menuService.listCategories());
    }

    // ── POST /menu/categories ─────────────────────────────────────────────────

    @Operation(
        summary = "Create a menu category",
        description = "Creates a new category that can be assigned to menu items. The displayOrder field controls the sort position in the menu listing. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Category created successfully"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role")
    })
    @PostMapping
    public ResponseEntity<MenuDtos.CategoryResponse> createCategory(
            @RequestBody  MenuDtos.CreateCategoryRequest request,
            @Parameter(hidden = true) @RequestHeader("X-User-Id")   String userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole) {
        log.info("POST /menu/categories userId={} role={}", userId, userRole);
        assertAdmin(userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.createCategory(request));
    }

    // ── PUT /menu/categories/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "Update a menu category",
        description = "Performs a partial update on the specified category — only fields present in the request body are changed. Evicts the categories Redis cache. Requires OFFICE_ADMIN role.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category updated successfully"),
        @ApiResponse(responseCode = "403", description = "Caller does not have OFFICE_ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @PutMapping("/{id}")
    public ResponseEntity<MenuDtos.CategoryResponse> updateCategory(
            @Parameter(description = "UUID of the category to update", required = true)
            @PathVariable String id,
            @RequestBody  MenuDtos.UpdateCategoryRequest request,
            @Parameter(hidden = true) @RequestHeader("X-User-Id")   String userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole) {
        log.info("PUT /menu/categories/{} userId={} role={}", id, userId, userRole);
        assertAdmin(userRole);
        return ResponseEntity.ok(menuService.updateCategory(id, request));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void assertAdmin(String userRole) {
        if (!"HEAD_OFFICE_ADMIN".equals(userRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HEAD_OFFICE_ADMIN can perform this action");
        }
    }
}
