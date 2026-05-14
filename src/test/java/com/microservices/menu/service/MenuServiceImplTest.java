package com.microservices.menu.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuCategory;
import com.microservices.menu.entity.MenuItem;
import com.microservices.menu.repository.MenuCategoryRepository;
import com.microservices.menu.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private MenuCategoryRepository menuCategoryRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private RuleBasedRecommendationService ruleBasedRecommendationService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @InjectMocks
    private MenuServiceImpl menuService;

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private MenuCategory category() {
        return MenuCategory.builder()
                .id("cat-1").name("Mains").displayOrder(1).active(true).build();
    }

    private MenuItem activeItem() {
        return MenuItem.builder()
                .id("item-1").name("Jollof Rice").description("Nigerian classic")
                .category(category()).basePrice(new BigDecimal("1500.00"))
                .imageUrl(null).active(true).build();
    }

    private MenuItem inactiveItem() {
        return MenuItem.builder()
                .id("item-1").name("Jollof Rice").description("Nigerian classic")
                .category(category()).basePrice(new BigDecimal("1500.00"))
                .active(false).build();
    }

    @BeforeEach
    void setUpRedis() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── createMenuItem ─────────────────────────────────────────────────────────

    @Nested
    class CreateMenuItem {

        @Test
        void savesItemAndPublishesCreatedEvent() {
            var req = new MenuDtos.CreateMenuItemRequest(
                    "Jollof Rice", "Nigerian classic", "cat-1", new BigDecimal("1500.00"), null);
            when(menuCategoryRepository.findById("cat-1")).thenReturn(Optional.of(category()));
            when(menuItemRepository.save(any())).thenReturn(activeItem());

            MenuDtos.MenuItemResponse result = menuService.createMenuItem(req);

            assertThat(result.id()).isEqualTo("item-1");
            assertThat(result.name()).isEqualTo("Jollof Rice");
            assertThat(result.categoryName()).isEqualTo("Mains");
            assertThat(result.active()).isTrue();

            verify(menuItemRepository).save(any(MenuItem.class));
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("CREATED"));
        }

        @Test
        void throwsNotFoundWhenCategoryDoesNotExist() {
            var req = new MenuDtos.CreateMenuItemRequest(
                    "Jollof Rice", "Desc", "bad-cat", new BigDecimal("1500.00"), null);
            when(menuCategoryRepository.findById("bad-cat")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.createMenuItem(req))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verifyNoInteractions(menuItemRepository);
        }

        @Test
        void handlesNullCategoryId() {
            var req = new MenuDtos.CreateMenuItemRequest(
                    "Plain Rice", "Desc", null, new BigDecimal("500.00"), null);
            MenuItem itemNoCategory = MenuItem.builder()
                    .id("item-2").name("Plain Rice").basePrice(new BigDecimal("500.00")).active(true).build();
            when(menuItemRepository.save(any())).thenReturn(itemNoCategory);

            MenuDtos.MenuItemResponse result = menuService.createMenuItem(req);

            assertThat(result.categoryId()).isNull();
            assertThat(result.categoryName()).isNull();
            verifyNoInteractions(menuCategoryRepository);
        }
    }

    // ── listMenuItems ──────────────────────────────────────────────────────────

    @Nested
    class ListMenuItems {

        private final Pageable pageable = PageRequest.of(0, 20);

        @Test
        void noFilters_callsFindAll() {
            when(menuItemRepository.findAll(pageable))
                    .thenReturn(new PageImpl<>(List.of(activeItem())));

            var page = menuService.listMenuItems(null, null, pageable);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).name()).isEqualTo("Jollof Rice");
            verify(menuItemRepository).findAll(pageable);
        }

        @Test
        void categoryIdOnly_callsFindByCategory() {
            when(menuItemRepository.findByCategory_Id("cat-1", pageable))
                    .thenReturn(new PageImpl<>(List.of(activeItem())));

            var page = menuService.listMenuItems("cat-1", null, pageable);

            assertThat(page.getContent()).hasSize(1);
            verify(menuItemRepository).findByCategory_Id("cat-1", pageable);
        }

        @Test
        void activeOnly_callsFindByActive() {
            when(menuItemRepository.findByActive(true, pageable))
                    .thenReturn(new PageImpl<>(List.of(activeItem())));

            var page = menuService.listMenuItems(null, true, pageable);

            assertThat(page.getContent()).hasSize(1);
            verify(menuItemRepository).findByActive(true, pageable);
        }

        @Test
        void bothFilters_callsFindByCategoryAndActive() {
            when(menuItemRepository.findByCategory_IdAndActive("cat-1", true, pageable))
                    .thenReturn(new PageImpl<>(List.of(activeItem())));

            var page = menuService.listMenuItems("cat-1", true, pageable);

            assertThat(page.getContent()).hasSize(1);
            verify(menuItemRepository).findByCategory_IdAndActive("cat-1", true, pageable);
        }
    }

    // ── getMenuItem ────────────────────────────────────────────────────────────

    @Nested
    class GetMenuItem {

        @Test
        void cacheMiss_hitsRepositoryAndCaches() {
            when(valueOps.get("menu:item:item-1")).thenReturn(null);
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(activeItem()));

            MenuDtos.MenuItemResponse result = menuService.getMenuItem("item-1");

            assertThat(result.id()).isEqualTo("item-1");
            assertThat(result.name()).isEqualTo("Jollof Rice");
            verify(menuItemRepository).findById("item-1");
            verify(valueOps).set(eq("menu:item:item-1"), anyString(), any());
        }

        @Test
        void cacheHit_returnsDeserializedDtoWithoutHittingRepository() throws Exception {
            MenuDtos.MenuItemResponse cached = new MenuDtos.MenuItemResponse(
                    "item-1", "Jollof Rice", "Nigerian classic",
                    "cat-1", "Mains", new BigDecimal("1500.00"),
                    null, true, null, null);
            String json = objectMapper.writeValueAsString(cached);
            when(valueOps.get("menu:item:item-1")).thenReturn(json);

            MenuDtos.MenuItemResponse result = menuService.getMenuItem("item-1");

            assertThat(result.id()).isEqualTo("item-1");
            assertThat(result.name()).isEqualTo("Jollof Rice");
            verifyNoInteractions(menuItemRepository);
        }

        @Test
        void notFound_throws404() {
            when(valueOps.get(anyString())).thenReturn(null);
            when(menuItemRepository.findById("bad-id")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.getMenuItem("bad-id"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── updateMenuItem ─────────────────────────────────────────────────────────

    @Nested
    class UpdateMenuItem {

        @Test
        void updatesAllFieldsAndPublishesUpdatedEvent() {
            MenuItem item = activeItem();
            var req = new MenuDtos.UpdateMenuItemRequest(
                    "New Name", "New Desc", "cat-1", new BigDecimal("2000.00"), "http://img.com/new.jpg");
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuCategoryRepository.findById("cat-1")).thenReturn(Optional.of(category()));
            when(menuItemRepository.save(item)).thenReturn(item);

            MenuDtos.MenuItemResponse result = menuService.updateMenuItem("item-1", req);

            assertThat(result.name()).isEqualTo("New Name");
            verify(menuItemRepository).save(item);
            verify(redisTemplate).delete("menu:item:item-1");
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("UPDATED"));
        }

        @Test
        void partialUpdate_onlyChangesProvidedFields() {
            MenuItem item = activeItem();
            var req = new MenuDtos.UpdateMenuItemRequest("Renamed Rice", null, null, null, null);
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuItemRepository.save(item)).thenReturn(item);

            menuService.updateMenuItem("item-1", req);

            assertThat(item.getName()).isEqualTo("Renamed Rice");
            assertThat(item.getDescription()).isEqualTo("Nigerian classic");
            assertThat(item.getBasePrice()).isEqualTo(new BigDecimal("1500.00"));
        }

        @Test
        void notFound_throws404() {
            when(menuItemRepository.findById("bad-id")).thenReturn(Optional.empty());
            var req = new MenuDtos.UpdateMenuItemRequest("Name", null, null, null, null);

            assertThatThrownBy(() -> menuService.updateMenuItem("bad-id", req))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── setItemActive ──────────────────────────────────────────────────────────

    @Nested
    class SetItemActive {

        @Test
        void activate_setsActiveTrueAndPublishesActivatedEvent() {
            MenuItem item = inactiveItem();
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuItemRepository.save(item)).thenReturn(item);

            MenuDtos.MenuItemResponse result = menuService.setItemActive("item-1", true);

            assertThat(result.active()).isTrue();
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("ACTIVATED"));
            verify(redisTemplate).delete("menu:item:item-1");
        }

        @Test
        void deactivate_setsActiveFalseAndPublishesDeactivatedEvent() {
            MenuItem item = activeItem();
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuItemRepository.save(item)).thenReturn(item);

            MenuDtos.MenuItemResponse result = menuService.setItemActive("item-1", false);

            assertThat(result.active()).isFalse();
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("DEACTIVATED"));
        }

        @Test
        void notFound_throws404() {
            when(menuItemRepository.findById("bad-id")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.setItemActive("bad-id", true))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── toggleItemActive ───────────────────────────────────────────────────────

    @Nested
    class ToggleItemActive {

        @Test
        void activeItem_becomesInactiveAndPublishesDeactivated() {
            MenuItem item = activeItem();
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuItemRepository.save(item)).thenReturn(item);

            MenuDtos.MenuItemResponse result = menuService.toggleItemActive("item-1");

            assertThat(result.active()).isFalse();
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("DEACTIVATED"));
            verify(redisTemplate).delete("menu:item:item-1");
        }

        @Test
        void inactiveItem_becomesActiveAndPublishesActivated() {
            MenuItem item = inactiveItem();
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));
            when(menuItemRepository.save(item)).thenReturn(item);

            MenuDtos.MenuItemResponse result = menuService.toggleItemActive("item-1");

            assertThat(result.active()).isTrue();
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("ACTIVATED"));
        }

        @Test
        void notFound_throws404() {
            when(menuItemRepository.findById("bad-id")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.toggleItemActive("bad-id"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── deleteMenuItem ─────────────────────────────────────────────────────────

    @Nested
    class DeleteMenuItem {

        @Test
        void deletesItemEvictsCacheAndPublishesDeletedEvent() {
            MenuItem item = activeItem();
            when(menuItemRepository.findById("item-1")).thenReturn(Optional.of(item));

            menuService.deleteMenuItem("item-1");

            verify(menuItemRepository).delete(item);
            verify(redisTemplate).delete("menu:item:item-1");
            verify(kafkaTemplate).send(eq("menu-item-events"), eq("item-1"), contains("DELETED"));
        }

        @Test
        void notFound_throws404() {
            when(menuItemRepository.findById("bad-id")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.deleteMenuItem("bad-id"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(menuItemRepository).findById("bad-id");
            verifyNoInteractions(kafkaTemplate);
        }
    }

    // ── createCategory ─────────────────────────────────────────────────────────

    @Nested
    class CreateCategory {

        @Test
        void savesCategoryAndEvictsCategoriesCache() {
            var req = new MenuDtos.CreateCategoryRequest("Grills", 3);
            MenuCategory saved = MenuCategory.builder()
                    .id("cat-3").name("Grills").displayOrder(3).active(true).build();
            when(menuCategoryRepository.save(any())).thenReturn(saved);

            MenuDtos.CategoryResponse result = menuService.createCategory(req);

            assertThat(result.id()).isEqualTo("cat-3");
            assertThat(result.name()).isEqualTo("Grills");
            assertThat(result.displayOrder()).isEqualTo(3);
            assertThat(result.active()).isTrue();
            verify(menuCategoryRepository).save(any(MenuCategory.class));
            verify(redisTemplate).delete("menu:categories");
        }
    }

    // ── listCategories ─────────────────────────────────────────────────────────

    @Nested
    class ListCategories {

        @Test
        void cacheMiss_hitsRepositoryAndCaches() {
            when(valueOps.get("menu:categories")).thenReturn(null);
            when(menuCategoryRepository.findByActiveTrueOrderByDisplayOrderAsc())
                    .thenReturn(List.of(category()));

            List<MenuDtos.CategoryResponse> result = menuService.listCategories();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Mains");
            verify(menuCategoryRepository).findByActiveTrueOrderByDisplayOrderAsc();
            verify(valueOps).set(eq("menu:categories"), anyString(), any());
        }

        @Test
        void cacheHit_returnsDeserializedListWithoutHittingRepository() throws Exception {
            List<MenuDtos.CategoryResponse> categories = List.of(
                    new MenuDtos.CategoryResponse("cat-1", "Mains", 1, true));
            String json = objectMapper.writeValueAsString(categories);
            when(valueOps.get("menu:categories")).thenReturn(json);

            List<MenuDtos.CategoryResponse> result = menuService.listCategories();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Mains");
            verifyNoInteractions(menuCategoryRepository);
        }
    }

    // ── getActiveBranchMenu ────────────────────────────────────────────────────

    @Nested
    class GetActiveBranchMenu {

        @Test
        void returnsOnlyActiveItems() {
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(activeItem()));

            List<MenuDtos.FrontendMenuItemResponse> result = menuService.getActiveBranchMenu("branch-1");

            assertThat(result).hasSize(1);
            MenuDtos.FrontendMenuItemResponse item = result.get(0);
            assertThat(item.id()).isEqualTo("item-1");
            assertThat(item.name()).isEqualTo("Jollof Rice");
            assertThat(item.available()).isTrue();
            assertThat(item.isActive()).isTrue();
            verify(menuItemRepository).findByActiveTrue();
        }

        @Test
        void mapsFieldsCorrectly_priceAndCategory() {
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(activeItem()));

            List<MenuDtos.FrontendMenuItemResponse> result = menuService.getActiveBranchMenu("branch-1");

            MenuDtos.FrontendMenuItemResponse item = result.get(0);
            assertThat(item.price()).isEqualTo(1500.00);
            assertThat(item.category()).isEqualTo("Mains");
            assertThat(item.image()).isEqualTo(item.imageUrl());
        }

        @Test
        void branchIdIsIgnored_alwaysReturnsAllActiveItems() {
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(activeItem()));

            menuService.getActiveBranchMenu("any-branch-id");

            // branchId is currently a no-op — only findByActiveTrue is called
            verify(menuItemRepository).findByActiveTrue();
        }

        @Test
        void inactiveItemsAreNotReturned() {
            // findByActiveTrue returns only active items — inactive items never appear
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of());

            List<MenuDtos.FrontendMenuItemResponse> result = menuService.getActiveBranchMenu("branch-1");

            assertThat(result).isEmpty();
        }

        @Test
        void itemWithNullCategory_useEmptyStringForCategory() {
            MenuItem itemNoCategory = MenuItem.builder()
                    .id("item-2").name("Plain Rice").description("Simple")
                    .category(null).basePrice(new BigDecimal("500.00"))
                    .imageUrl(null).active(true).build();
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(itemNoCategory));

            List<MenuDtos.FrontendMenuItemResponse> result = menuService.getActiveBranchMenu("branch-1");

            assertThat(result.get(0).category()).isEqualTo("");
            assertThat(result.get(0).price()).isEqualTo(500.00);
        }

        @Test
        void itemWithNullBasePrice_usesZeroForPrice() {
            MenuItem itemNoPrice = MenuItem.builder()
                    .id("item-3").name("Freebie").description("Free item")
                    .category(category()).basePrice(null)
                    .imageUrl(null).active(true).build();
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(itemNoPrice));

            List<MenuDtos.FrontendMenuItemResponse> result = menuService.getActiveBranchMenu("branch-1");

            assertThat(result.get(0).price()).isEqualTo(0.0);
        }
    }

    // ── suggestFood ───────────────────────────────────────────────────────────

    @Nested
    class SuggestFood {

        @Test
        void incompleteRequest_returnsGuidingQuestions() {
            MenuDtos.AiRecommendationResponse result = menuService.suggestFood(
                    new MenuDtos.FoodSuggestionRequest(null, null, null, null, null, List.of(), null, null, null));

            assertThat(result.readyForSuggestions()).isFalse();
            assertThat(result.suggestions()).isEmpty();
            assertThat(result.questions()).contains(
                    "What is your budget?",
                    "Do you want pickup, delivery, or dine-in?"
            );
            verifyNoInteractions(menuItemRepository);
        }

        @Test
        void completeRequest_whenGeminiUnavailable_usesRuleBasedFallback() {
            MenuCategory mains = MenuCategory.builder()
                    .id("cat-1").name("Mains").displayOrder(1).active(true).build();
            MenuItem jollof = MenuItem.builder()
                    .id("item-1").name("Spicy Jollof Rice").description("Rice with pepper and chicken")
                    .category(mains).basePrice(new BigDecimal("1500.00"))
                    .active(true).build();
            MenuItem cake = MenuItem.builder()
                    .id("item-2").name("Chocolate Cake").description("Sweet dessert")
                    .category(MenuCategory.builder().id("cat-2").name("Desserts").build())
                    .basePrice(new BigDecimal("2500.00"))
                    .active(true).build();
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of(cake, jollof));

            MenuDtos.AiRecommendationResponse mockRuleResponse = new MenuDtos.AiRecommendationResponse(
                    "RULE_BASED", true,
                    "Here are balanced combo recommendations.",
                    true, List.of(),
                    List.of(new MenuDtos.ComboSuggestion(
                            "Balanced Meal Combo",
                            List.of(new MenuDtos.ComboItem("item-1", "Spicy Jollof Rice", new BigDecimal("1500.00"))),
                            new BigDecimal("1500.00"), 72,
                            List.of("Balanced", "High Protein"),
                            "Spicy Jollof Rice leads this combo within your budget.",
                            0.83)),
                    new BigDecimal("1500.00"));
            when(ruleBasedRecommendationService.recommend(any(), any())).thenReturn(mockRuleResponse);

            MenuDtos.FoodSuggestionRequest request = new MenuDtos.FoodSuggestionRequest(
                    "branch-1", "Lekki Branch",
                    new BigDecimal("3000.00"), "lunch", "heavy",
                    List.of("spicy", "high protein"), 1, "delivery", 3);

            MenuDtos.AiRecommendationResponse result = menuService.suggestFood(request);

            assertThat(result.readyForSuggestions()).isTrue();
            assertThat(result.fallbackUsed()).isTrue();
            assertThat(result.recommendationSource()).isEqualTo("RULE_BASED");
            assertThat(result.questions()).isEmpty();
            assertThat(result.suggestions()).hasSize(1);
            assertThat(result.suggestions().get(0).comboName()).isEqualTo("Balanced Meal Combo");
            assertThat(result.suggestions().get(0).items().get(0).name()).isEqualTo("Spicy Jollof Rice");
            assertThat(result.suggestions().get(0).healthScore()).isEqualTo(72);
        }

        @Test
        void emptyMenu_returnsNotReadyResponse() {
            when(menuItemRepository.findByActiveTrue()).thenReturn(List.of());

            MenuDtos.FoodSuggestionRequest request = new MenuDtos.FoodSuggestionRequest(
                    "branch-1", "Lekki Branch",
                    new BigDecimal("3000.00"), "lunch", "heavy",
                    List.of("spicy"), 1, "delivery", 3);

            MenuDtos.AiRecommendationResponse result = menuService.suggestFood(request);

            assertThat(result.readyForSuggestions()).isTrue();
            assertThat(result.suggestions()).isEmpty();
            verifyNoInteractions(ruleBasedRecommendationService);
        }
    }

    // ── listCategoryNames ──────────────────────────────────────────────────────

    @Nested
    class ListCategoryNames {

        @Test
        void returnsNamesOfActiveCategories() {
            MenuCategory cat1 = MenuCategory.builder()
                    .id("cat-1").name("Mains").displayOrder(1).active(true).build();
            MenuCategory cat2 = MenuCategory.builder()
                    .id("cat-2").name("Drinks").displayOrder(2).active(true).build();
            when(menuCategoryRepository.findByActiveTrueOrderByDisplayOrderAsc())
                    .thenReturn(List.of(cat1, cat2));

            List<String> result = menuService.listCategoryNames();

            assertThat(result).containsExactly("Mains", "Drinks");
            verify(menuCategoryRepository).findByActiveTrueOrderByDisplayOrderAsc();
        }

        @Test
        void returnsEmptyListWhenNoActiveCategories() {
            when(menuCategoryRepository.findByActiveTrueOrderByDisplayOrderAsc())
                    .thenReturn(List.of());

            List<String> result = menuService.listCategoryNames();

            assertThat(result).isEmpty();
        }

        @Test
        void preservesDisplayOrder() {
            MenuCategory cat1 = MenuCategory.builder()
                    .id("cat-1").name("Starters").displayOrder(1).active(true).build();
            MenuCategory cat2 = MenuCategory.builder()
                    .id("cat-2").name("Mains").displayOrder(2).active(true).build();
            MenuCategory cat3 = MenuCategory.builder()
                    .id("cat-3").name("Desserts").displayOrder(3).active(true).build();
            when(menuCategoryRepository.findByActiveTrueOrderByDisplayOrderAsc())
                    .thenReturn(List.of(cat1, cat2, cat3));

            List<String> result = menuService.listCategoryNames();

            assertThat(result).containsExactly("Starters", "Mains", "Desserts");
        }
    }

    // ── updateCategory ─────────────────────────────────────────────────────────

    @Nested
    class UpdateCategory {

        @Test
        void updatesNameAndDisplayOrderAndEvictsCategoriesCache() {
            MenuCategory cat = category();
            var req = new MenuDtos.UpdateCategoryRequest("Updated Mains", 10);
            when(menuCategoryRepository.findById("cat-1")).thenReturn(Optional.of(cat));
            when(menuCategoryRepository.save(cat)).thenReturn(cat);

            MenuDtos.CategoryResponse result = menuService.updateCategory("cat-1", req);

            assertThat(cat.getName()).isEqualTo("Updated Mains");
            assertThat(cat.getDisplayOrder()).isEqualTo(10);
            verify(menuCategoryRepository).save(cat);
            verify(redisTemplate).delete("menu:categories");
        }

        @Test
        void partialUpdate_nameOnly_doesNotChangeDisplayOrder() {
            MenuCategory cat = category();
            var req = new MenuDtos.UpdateCategoryRequest("Renamed", null);
            when(menuCategoryRepository.findById("cat-1")).thenReturn(Optional.of(cat));
            when(menuCategoryRepository.save(cat)).thenReturn(cat);

            menuService.updateCategory("cat-1", req);

            assertThat(cat.getName()).isEqualTo("Renamed");
            assertThat(cat.getDisplayOrder()).isEqualTo(1);
        }

        @Test
        void notFound_throws404() {
            when(menuCategoryRepository.findById("bad-id")).thenReturn(Optional.empty());
            var req = new MenuDtos.UpdateCategoryRequest("Name", null);

            assertThatThrownBy(() -> menuService.updateCategory("bad-id", req))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
