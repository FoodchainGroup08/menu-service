package com.microservices.menu.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuCategoryController.class)
class MenuCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MenuService menuService;

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private MenuDtos.CategoryResponse categoryResponse(String id, String name, int order) {
        return new MenuDtos.CategoryResponse(id, name, order, true);
    }

    // ── GET /menu/categories ───────────────────────────────────────────────────

    @Test
    void listCategories_returns200WithList() throws Exception {
        when(menuService.listCategories()).thenReturn(List.of(
                categoryResponse("cat-1", "Mains",  1),
                categoryResponse("cat-2", "Drinks", 2)
        ));

        mockMvc.perform(get("/menu/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Mains"))
                .andExpect(jsonPath("$[1].name").value("Drinks"));
    }

    @Test
    void listCategories_empty_returns200WithEmptyArray() throws Exception {
        when(menuService.listCategories()).thenReturn(List.of());

        mockMvc.perform(get("/menu/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listCategories_namesOnly_returnsStringArray() throws Exception {
        when(menuService.listCategoryNames()).thenReturn(List.of("Mains", "Drinks", "Desserts"));

        mockMvc.perform(get("/menu/categories").param("namesOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0]").value("Mains"))
                .andExpect(jsonPath("$[1]").value("Drinks"))
                .andExpect(jsonPath("$[2]").value("Desserts"));

        verify(menuService).listCategoryNames();
        verify(menuService, never()).listCategories();
    }

    @Test
    void listCategories_namesOnlyFalse_returnsFullObjects() throws Exception {
        when(menuService.listCategories()).thenReturn(List.of(
                categoryResponse("cat-1", "Mains", 1)));

        mockMvc.perform(get("/menu/categories").param("namesOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cat-1"))
                .andExpect(jsonPath("$[0].name").value("Mains"));

        verify(menuService).listCategories();
        verify(menuService, never()).listCategoryNames();
    }

    @Test
    void listCategories_namesOnly_empty_returnsEmptyStringArray() throws Exception {
        when(menuService.listCategoryNames()).thenReturn(List.of());

        mockMvc.perform(get("/menu/categories").param("namesOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── POST /menu/categories ──────────────────────────────────────────────────

    @Test
    void createCategory_asAdmin_returns201() throws Exception {
        var req = new MenuDtos.CreateCategoryRequest("Grills", 3);
        when(menuService.createCategory(any())).thenReturn(categoryResponse("cat-3", "Grills", 3));

        mockMvc.perform(post("/menu/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cat-3"))
                .andExpect(jsonPath("$.name").value("Grills"))
                .andExpect(jsonPath("$.displayOrder").value(3))
                .andExpect(jsonPath("$.active").value(true));

        verify(menuService).createCategory(any());
    }

    @Test
    void createCategory_nonAdmin_returns403AndDoesNotCallService() throws Exception {
        var req = new MenuDtos.CreateCategoryRequest("Grills", 3);

        mockMvc.perform(post("/menu/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-2")
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ── PUT /menu/categories/{id} ──────────────────────────────────────────────

    @Test
    void updateCategory_asAdmin_returns200() throws Exception {
        var req = new MenuDtos.UpdateCategoryRequest("Updated Mains", 10);
        when(menuService.updateCategory(eq("cat-1"), any()))
                .thenReturn(categoryResponse("cat-1", "Updated Mains", 10));

        mockMvc.perform(put("/menu/categories/cat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Mains"))
                .andExpect(jsonPath("$.displayOrder").value(10));

        verify(menuService).updateCategory(eq("cat-1"), any());
    }

    @Test
    void updateCategory_notFound_returns404() throws Exception {
        var req = new MenuDtos.UpdateCategoryRequest("Name", null);
        when(menuService.updateCategory(eq("bad-id"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: bad-id"));

        mockMvc.perform(put("/menu/categories/bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateCategory_nonAdmin_returns403() throws Exception {
        var req = new MenuDtos.UpdateCategoryRequest("Name", null);

        mockMvc.perform(put("/menu/categories/cat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-2")
                        .header("X-User-Role", "STAFF"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    @Test
    void updateCategory_partialUpdate_nameOnly_returns200() throws Exception {
        var req = new MenuDtos.UpdateCategoryRequest("Soups Only", null);
        when(menuService.updateCategory(eq("cat-1"), any()))
                .thenReturn(categoryResponse("cat-1", "Soups Only", 1));

        mockMvc.perform(put("/menu/categories/cat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-User-Id",   "user-1")
                        .header("X-User-Role", "OFFICE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Soups Only"));
    }
}
