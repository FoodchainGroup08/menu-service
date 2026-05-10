package com.microservices.menu.controller;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/v1/menu")
@Tag(name = "Branch Menu", description = "Returns the active menu for a branch. Through the API gateway this route requires a Bearer token.")
public class MenuBranchController {

    @Autowired
    private MenuService menuService;

    // ── GET /menu/branch/{branchId} ───────────────────────────────────────────

    @Operation(
        summary = "Get active menu for a branch",
        description = "Returns all currently available menu items for the specified branch. " +
                      "Since the menu catalogue is shared across branches, branchId is accepted " +
                      "for future branch-specific menus but currently returns all active items.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of active menu items")
    })
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<MenuDtos.FrontendMenuItemResponse>> getBranchMenu(
            @Parameter(description = "UUID of the branch", required = true)
            @PathVariable String branchId) {
        log.info("GET /menu/branch/{}", branchId);
        return ResponseEntity.ok(menuService.getActiveBranchMenu(branchId));
    }
}
