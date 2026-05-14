package com.microservices.menu.service;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuCategory;
import com.microservices.menu.entity.MenuItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRecommendationServiceTest {

    private RuleBasedRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RuleBasedRecommendationService(new NutritionScoringService());
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private MenuCategory cat(String id, String name) {
        return MenuCategory.builder().id(id).name(name).displayOrder(1).active(true).build();
    }

    private MenuItem item(String id, String name, String catName, double price) {
        return MenuItem.builder()
                .id(id).name(name).description("")
                .category(cat("cat-" + id, catName))
                .basePrice(BigDecimal.valueOf(price))
                .active(true).build();
    }

    private MenuDtos.FoodSuggestionRequest fullRequest(double budget, String mealType, String appetite, int people) {
        return new MenuDtos.FoodSuggestionRequest(
                "branch-1", "Test Branch",
                BigDecimal.valueOf(budget), mealType, appetite,
                List.of(), people, "delivery", 5);
    }

    // ── Empty / null input ─────────────────────────────────────────────────────

    @Nested
    class EdgeCases {

        @Test
        void emptyMenu_returnsReadyWithEmptySuggestions() {
            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 1), List.of());

            assertThat(result.readyForSuggestions()).isTrue();
            assertThat(result.suggestions()).isEmpty();
            assertThat(result.recommendationSource()).isEqualTo("RULE_BASED");
        }

        @Test
        void nullRequest_doesNotThrow() {
            MenuItem main = item("1", "Jollof Rice", "Mains", 1500);
            MenuDtos.AiRecommendationResponse result = service.recommend(null, List.of(main));

            assertThat(result.readyForSuggestions()).isTrue();
            assertThat(result.suggestions()).isNotEmpty();
        }
    }

    // ── Combo generation ───────────────────────────────────────────────────────

    @Nested
    class ComboGeneration {

        @Test
        void mainPlusSidePlusDrink_buildsThreeItemCombo() {
            MenuItem main  = item("1", "Grilled Chicken", "Mains",  2500);
            MenuItem side  = item("2", "Garden Salad",    "Sides",   800);
            MenuItem drink = item("3", "Fresh Juice",     "Drinks",  600);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(10000, "lunch", "heavy", 1), List.of(main, side, drink));

            assertThat(result.readyForSuggestions()).isTrue();
            assertThat(result.suggestions()).isNotEmpty();

            MenuDtos.ComboSuggestion combo = result.suggestions().get(0);
            assertThat(combo.items()).hasSize(3);
            assertThat(combo.totalPrice()).isEqualByComparingTo("3900.00");
        }

        @Test
        void onlyMains_buildsSingleItemFallbackCombos() {
            MenuItem main1 = item("1", "Jollof Rice", "Mains", 1500);
            MenuItem main2 = item("2", "Fried Rice",  "Mains", 1800);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 1), List.of(main1, main2));

            assertThat(result.suggestions()).isNotEmpty();
            assertThat(result.suggestions().get(0).items()).hasSize(1);
        }

        @Test
        void limitIsRespected() {
            List<MenuItem> items = List.of(
                    item("1", "Dish A", "Mains", 1000),
                    item("2", "Dish B", "Mains", 1200),
                    item("3", "Dish C", "Mains", 1400),
                    item("4", "Dish D", "Mains", 1600),
                    item("5", "Dish E", "Mains", 1800),
                    item("6", "Dish F", "Mains", 2000));

            MenuDtos.FoodSuggestionRequest req = new MenuDtos.FoodSuggestionRequest(
                    "b", "B", BigDecimal.valueOf(5000), "lunch", "heavy",
                    List.of(), 1, "delivery", 3);

            MenuDtos.AiRecommendationResponse result = service.recommend(req, items);

            assertThat(result.suggestions().size()).isLessThanOrEqualTo(3);
        }
    }

    // ── Budget filtering ───────────────────────────────────────────────────────

    @Nested
    class BudgetFiltering {

        @Test
        void comboExceedingBudget_isExcluded() {
            MenuItem expensive = item("1", "Lobster Platter", "Mains", 15000);
            MenuItem affordable = item("2", "Jollof Rice",   "Mains",  1500);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(2000, "lunch", "heavy", 1), List.of(expensive, affordable));

            assertThat(result.suggestions()).isNotEmpty();
            result.suggestions().forEach(combo ->
                    assertThat(combo.totalPrice()).isLessThanOrEqualTo(BigDecimal.valueOf(2000)));
        }
    }

    // ── Health scoring ─────────────────────────────────────────────────────────

    @Nested
    class HealthScoring {

        @Test
        void grilledChickenScoresHigherThanFriedBurger() {
            MenuItem grilled = item("1", "Grilled Chicken Salad", "Mains", 2000);
            MenuItem burger  = item("2", "Deep Fried Burger",     "Mains", 1800);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 1), List.of(grilled, burger));

            assertThat(result.suggestions()).isNotEmpty();
            assertThat(result.suggestions().get(0).items().get(0).name())
                    .isEqualTo("Grilled Chicken Salad");
        }

        @Test
        void comboHealthScoreIsWithinRange() {
            MenuItem main  = item("1", "Grilled Fish",  "Mains",  2200);
            MenuItem drink = item("2", "Water",         "Drinks",  200);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 1), List.of(main, drink));

            result.suggestions().forEach(combo -> {
                assertThat(combo.healthScore()).isGreaterThanOrEqualTo(10);
                assertThat(combo.healthScore()).isLessThanOrEqualTo(100);
            });
        }

        @Test
        void balancedWellnessTagPresentForHealthyCombo() {
            MenuItem main  = item("1", "Grilled Chicken", "Mains",  2500);
            MenuItem side  = item("2", "Vegetable Soup",  "Sides",   900);
            MenuItem drink = item("3", "Fresh Juice",     "Drinks",  600);

            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(10000, "lunch", "heavy", 1), List.of(main, side, drink));

            assertThat(result.suggestions()).isNotEmpty();
            MenuDtos.ComboSuggestion top = result.suggestions().get(0);
            assertThat(top.wellnessTags()).isNotEmpty();
        }
    }

    // ── Metadata ───────────────────────────────────────────────────────────────

    @Nested
    class ResponseMetadata {

        @Test
        void recommendationSourceIsRuleBased() {
            MenuItem main = item("1", "Jollof Rice", "Mains", 1500);
            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 1), List.of(main));

            assertThat(result.recommendationSource()).isEqualTo("RULE_BASED");
            assertThat(result.fallbackUsed()).isTrue();
        }

        @Test
        void estimatedTotalCostIsSetForFirstCombo() {
            MenuItem main = item("1", "Jollof Rice", "Mains", 1500);
            MenuDtos.AiRecommendationResponse result = service.recommend(
                    fullRequest(5000, "lunch", "heavy", 2), List.of(main));

            assertThat(result.estimatedTotalCost()).isGreaterThan(BigDecimal.ZERO);
        }
    }
}
