package com.microservices.menu.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuItemController.class)
class MenuItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MenuService menuService;

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private MenuDtos.MenuItemResponse response(boolean active) {
        return new MenuDtos.MenuItemResponse(
                "item-1", "Jollof Rice", "Nigerian classic",
                "cat-1", "Mains", new BigDecimal("1500.00"),
                null, active, LocalDateTime.now(), LocalDateTime.now());
    }

    private MenuDtos.MenuItemSummary summary() {
        return new MenuDtos.MenuItemSummary(
                "item-1", "Jollof Rice", "cat-1", "Mains", new BigDecimal("1500.00"), true);
    }

    // ── GET /menu/items ────────────────────────────────────────────────────────

    @Test
    void listItems_noFilters_returns200WithPage() throws Exception {
        when(menuService.listMenuItems(null, null, any()))
                .thenReturn(new PageImpl<>(List.of(summary())));

        mockMvc.perform(get("/menu/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value("item-1"))
                .andExpect(jsonPath("$.content[0].name").value("Jollof Rice"))
                .andExpect(jsonPath("$.content[0].active").value(true));
    }

    @Test
    void listItems_withCategoryId_passesFilterToService() throws Exception {
        when(menuService.listMenuItems(eq("cat-1"), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(summary())));

        mockMvc.perform(get("/menu/items").param("categoryId", "cat-1"))
                .andExpect(status().isOk());

        verify(menuService).listMenuItems(eq("cat-1"), isNull(), any());
    }

    @Test
    void listItems_withActiveFilter_passesFilterToService() throws Exception {
        when(menuService.listMenuItems(isNull(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/menu/items").param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(menuService).listMenuItems(isNull(), eq(false), any());
    }

    @Test
    void listItems_withBothFilters_passesFiltersToService() throws Exception {
        when(menuService.listMenuItems(eq("cat-1"), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of(summary())));

        mockMvc.perform(get("/menu/items")
                        .param("categoryId", "cat-1")
                        .param("active", "true"))
                .andExpect(status().isOk());

        verify(menuService).listMenuItems(eq("cat-1"), eq(true), any());
    }

    // ── GET /menu/items/{id} ───────────────────────────────────────────────────

    @Test
    void getItem_exists_returns200WithItem() throws Exception {
        when(menuService.getMenuItem("item-1")).thenReturn(response(true));

        mockMvc.perform(get("/menu/items/item-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("item-1"))
                .andExpect(jsonPath("$.name").value("Jollof Rice"))
                .andExpect(jsonPath("$.categoryName").value("Mains"))
                .andExpect(jsonPath("$.basePrice").value(1500.00));
    }

    @Test
    void getItem_notFound_returns404() throws Exception {
        when(menuService.getMenuItem("unknown"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found: unknown"));

        mockMvc.perform(get("/menu/items/unknown"))
                .andExpect(status().isNotFound());
    }

    // ── POST /menu/items ───────────────────────────────────────────────────────

    @Test
    void createItem_asAdmin_returns201() throws Exception {
        var req = new MenuDtos.CreateMenuItemRequest(
                "Jollof Rice", "Nigerian classic", "cat-1", new BigDecimal("1500.00"), null);
        when(menuService.createMenuItem(any())).thenReturn(response(true));

        mockMvc.perform(post("/menu/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("item-1"))
                .andExpect(jsonPath("$.name").value("Jollof Rice"));

        verify(menuService).createMenuItem(any());
    }

    @Test
    void createItem_nonAdmin_returns403AndDoesNotCallService() throws Exception {
        var req = new MenuDtos.CreateMenuItemRequest(
                "Jollof Rice", "Nigerian classic", "cat-1", new BigDecimal("1500.00"), null);

        mockMvc.perform(post("/menu/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-2")
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ── PUT /menu/items/{id} ───────────────────────────────────────────────────

    @Test
    void updateItem_asAdmin_returns200() throws Exception {
        var req = new MenuDtos.UpdateMenuItemRequest("Updated Rice", null, null, new BigDecimal("2000.00"), null);
        when(menuService.updateMenuItem(eq("item-1"), any())).thenReturn(response(true));

        mockMvc.perform(put("/menu/items/item-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk());

        verify(menuService).updateMenuItem(eq("item-1"), any());
    }

    @Test
    void updateItem_notFound_returns404() throws Exception {
        var req = new MenuDtos.UpdateMenuItemRequest("Name", null, null, null, null);
        when(menuService.updateMenuItem(eq("bad-id"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found: bad-id"));

        mockMvc.perform(put("/menu/items/bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateItem_nonAdmin_returns403() throws Exception {
        var req = new MenuDtos.UpdateMenuItemRequest("Name", null, null, null, null);

        mockMvc.perform(put("/menu/items/item-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-2")
                        .header("X-User-Role", "STAFF"))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /menu/items/{id}/activate ────────────────────────────────────────

    @Test
    void activate_asAdmin_returns200WithActiveTrue() throws Exception {
        when(menuService.setItemActive("item-1", true)).thenReturn(response(true));

        mockMvc.perform(patch("/menu/items/item-1/activate")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void activate_nonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/menu/items/item-1/activate")
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ── PATCH /menu/items/{id}/deactivate ─────────────────────────────────────

    @Test
    void deactivate_asAdmin_returns200WithActiveFalse() throws Exception {
        when(menuService.setItemActive("item-1", false)).thenReturn(response(false));

        mockMvc.perform(patch("/menu/items/item-1/deactivate")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void deactivate_nonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/menu/items/item-1/deactivate")
                        .header("X-User-Role", "VIEWER"))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /menu/items/{id}/toggle ─────────────────────────────────────────

    @Test
    void toggle_asAdmin_returnsToggledResponse() throws Exception {
        when(menuService.toggleItemActive("item-1")).thenReturn(response(false));

        mockMvc.perform(patch("/menu/items/item-1/toggle")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(menuService).toggleItemActive("item-1");
    }

    @Test
    void toggle_notFound_returns404() throws Exception {
        when(menuService.toggleItemActive("bad-id"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found: bad-id"));

        mockMvc.perform(patch("/menu/items/bad-id/toggle")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggle_nonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/menu/items/item-1/toggle")
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ── DELETE /menu/items/{id} ────────────────────────────────────────────────

    @Test
    void deleteItem_asAdmin_returns200WithMessage() throws Exception {
        doNothing().when(menuService).deleteMenuItem("item-1");

        mockMvc.perform(delete("/menu/items/item-1")
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Menu item deleted successfully"))
                .andExpect(jsonPath("$.id").value("item-1"));

        verify(menuService).deleteMenuItem("item-1");
    }

    @Test
    void deleteItem_notFound_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found: bad-id"))
                .when(menuService).deleteMenuItem("bad-id");

        mockMvc.perform(delete("/menu/items/bad-id")
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteItem_nonAdmin_returns403AndDoesNotCallService() throws Exception {
        mockMvc.perform(delete("/menu/items/item-1")
                        .header("X-User-Id",   "user-2")
                        .header("X-User-Role", "VIEWER"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }
}
