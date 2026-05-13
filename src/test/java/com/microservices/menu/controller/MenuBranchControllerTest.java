package com.microservices.menu.controller;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.math.BigDecimal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuBranchController.class)
@AutoConfigureMockMvc(addFilters = false)
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

    // ── GET /v1/menu/branch/{branchId} ────────────────────────────────────────────

    @Test
    void getBranchMenu_returnsActiveItems_200() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/v1/menu/branch/branch-1"))
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

        mockMvc.perform(get("/v1/menu/branch/branch-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(menuService).getActiveBranchMenu("branch-2");
    }

    @Test
    void getBranchMenu_differentBranchIds_eachDelegatesToService() throws Exception {
        when(menuService.getActiveBranchMenu("branch-abc"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/v1/menu/branch/branch-abc"))
                .andExpect(status().isOk());

        verify(menuService).getActiveBranchMenu("branch-abc");
    }

    @Test
    void getBranchMenu_responseDoesNotContainBasePrice() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/v1/menu/branch/branch-1"))
                .andExpect(status().isOk())
                // frontend field is 'price', not 'basePrice'
                .andExpect(jsonPath("$[0].price").value(1500.00))
                .andExpect(jsonPath("$[0].basePrice").doesNotExist());
    }

    @Test
    void getBranchMenu_responseDoesNotContainActiveField() throws Exception {
        when(menuService.getActiveBranchMenu("branch-1"))
                .thenReturn(List.of(frontendItem(true)));

        mockMvc.perform(get("/v1/menu/branch/branch-1"))
                .andExpect(status().isOk())
                // frontend field is 'available', not 'active'
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].active").doesNotExist());
    }

    @Test
    void suggestFood_returnsSuggestions_200() throws Exception {
        MenuDtos.FoodSuggestionResponse response = new MenuDtos.FoodSuggestionResponse(
                "Here are my best picks from the available menu.",
                true,
                List.of(),
                List.of(new MenuDtos.FoodSuggestionItem(
                        "item-1",
                        "Jollof Rice",
                        new BigDecimal("1500.00"),
                        "fits your budget",
                        "branch-1",
                        "Lekki Branch",
                        new BigDecimal("1500.00"),
                        List.of("Add a drink")
                )),
                new BigDecimal("1500.00")
        );
        when(menuService.suggestFood(any(MenuDtos.FoodSuggestionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/v1/menu/suggestions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "branchId": "branch-1",
                                  "branchName": "Lekki Branch",
                                  "budget": 3000,
                                  "mealType": "lunch",
                                  "appetite": "heavy",
                                  "dietaryPreferences": ["spicy"],
                                  "peopleCount": 1,
                                  "fulfillmentType": "delivery"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForSuggestions").value(true))
                .andExpect(jsonPath("$.suggestions[0].menuItemName").value("Jollof Rice"))
                .andExpect(jsonPath("$.suggestions[0].branchName").value("Lekki Branch"))
                .andExpect(jsonPath("$.suggestions[0].estimatedTotalCost").value(1500.00));

        verify(menuService).suggestFood(any(MenuDtos.FoodSuggestionRequest.class));
    }
}
