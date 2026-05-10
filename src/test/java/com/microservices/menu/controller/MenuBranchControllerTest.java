package com.microservices.menu.controller;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuBranchController.class)
class MenuBranchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MenuService menuService;

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private MenuDtos.FrontendMenuItemResponse frontendItem(boolean available) {
        return new MenuDtos.FrontendMenuItemResponse(
                "item-1", "Jollof Rice", "Nigerian classic",
                1500.00, "Mains", available, available,
                "http://img.com/jollof.jpg", "http://img.com/jollof.jpg");
    }

    // ── GET /menu/branch/{branchId} ────────────────────────────────────────────

    @Test
    void getBranchMenu_returnsActiveItems_200() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/menu/branch/branch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("item-1"))
                .andExpect(jsonPath("$[0].name").value("Jollof Rice"))
                .andExpect(jsonPath("$[0].price").value(1500.00))
                .andExpect(jsonPath("$[0].category").value("Mains"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].imageUrl").value("http://img.com/jollof.jpg"))
                .andExpect(jsonPath("$[0].image").value("http://img.com/jollof.jpg"));

        verify(menuService).getActiveBranchMenu("branch-1");
    }

    @Test
    void getBranchMenu_emptyMenu_returns200WithEmptyArray() throws Exception {
        when(menuService.getActiveBranchMenu("branch-2")).thenReturn(List.of());

        mockMvc.perform(get("/menu/branch/branch-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(menuService).getActiveBranchMenu("branch-2");
    }

    @Test
    void getBranchMenu_differentBranchIds_eachDelegatesToService() throws Exception {
        when(menuService.getActiveBranchMenu("branch-abc"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/menu/branch/branch-abc"))
                .andExpect(status().isOk());

        verify(menuService).getActiveBranchMenu("branch-abc");
    }

    @Test
    void getBranchMenu_responseDoesNotContainBasePrice() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/menu/branch/branch-1"))
                .andExpect(status().isOk())
                // frontend field is 'price', not 'basePrice'
                .andExpect(jsonPath("$[0].price").value(1500.00))
                .andExpect(jsonPath("$[0].basePrice").doesNotExist());
    }

    @Test
    void getBranchMenu_responseDoesNotContainActiveField() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/menu/branch/branch-1"))
                .andExpect(status().isOk())
                // frontend field is 'available', not 'active'
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].active").doesNotExist());
    }
}
