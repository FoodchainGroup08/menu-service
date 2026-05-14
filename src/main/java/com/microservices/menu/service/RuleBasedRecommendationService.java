package com.microservices.menu.service;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleBasedRecommendationService {

    private final NutritionScoringService nutritionScoringService;

    public MenuDtos.AiRecommendationResponse recommend(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems
    ) {
        log.debug("Rule-based recommendation: {} active items, request={}", activeItems.size(), request);

        if (activeItems == null || activeItems.isEmpty()) {
            return fallbackEmpty("No menu items are currently available.");
        }

        List<MenuItem> budgetFiltered = filterByBudget(request, activeItems);
        List<MenuItem> pool = budgetFiltered.isEmpty() ? activeItems : budgetFiltered;

        Map<String, List<MenuItem>> byRole = groupByRole(pool);

        int limit   = request != null && request.limit() != null ? Math.min(Math.max(1, request.limit()), 10) : 5;
        int people  = request != null && request.peopleCount() != null ? Math.max(1, request.peopleCount()) : 1;
        String mealType = request != null ? request.mealType() : null;
        String appetite = request != null ? request.appetite() : null;

        List<MenuDtos.ComboSuggestion> combos = new ArrayList<>();

        // Primary: Main + Side + Drink combos
        combos.addAll(buildMainSideDrinkCombos(byRole, request, people, limit));

        // Secondary: Breakfast/snack/dessert specials when meal type suggests it
        if ("breakfast".equalsIgnoreCase(mealType) || "snack".equalsIgnoreCase(mealType)) {
            combos.addAll(buildLightCombos(byRole, request, people, limit));
        }

        // Fallback: top-scored single-item "combos" when categories are sparse
        if (combos.size() < limit) {
            combos.addAll(buildSingleItemFallbacks(pool, request, people, combos.size(), limit));
        }

        // Sort by health score descending, deduplicate by comboName
        List<MenuDtos.ComboSuggestion> result = combos.stream()
                .collect(Collectors.toMap(
                        MenuDtos.ComboSuggestion::comboName,
                        c -> c,
                        (a, b) -> a.healthScore() >= b.healthScore() ? a : b,
                        LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator.comparingInt(MenuDtos.ComboSuggestion::healthScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());

        if (result.isEmpty()) {
            return fallbackEmpty("No suitable combinations found for your preferences. Try relaxing your filters.");
        }

        BigDecimal estimatedTotal = result.get(0).totalPrice()
                .multiply(BigDecimal.valueOf(people))
                .setScale(2, RoundingMode.HALF_UP);

        return new MenuDtos.AiRecommendationResponse(
                "RULE_BASED",
                true,
                buildResultMessage(mealType, appetite, people),
                true,
                List.of(),
                result,
                estimatedTotal
        );
    }

    // ── Filtering ─────────────────────────────────────────────────────────────────

    private List<MenuItem> filterByBudget(MenuDtos.FoodSuggestionRequest req, List<MenuItem> items) {
        if (req == null || req.budget() == null || req.budget().compareTo(BigDecimal.ZERO) <= 0) return items;
        return items.stream()
                .filter(i -> i.getBasePrice() == null || i.getBasePrice().compareTo(req.budget()) <= 0)
                .collect(Collectors.toList());
    }

    // ── Categorisation ────────────────────────────────────────────────────────────

    private Map<String, List<MenuItem>> groupByRole(List<MenuItem> items) {
        Map<String, List<MenuItem>> roles = new LinkedHashMap<>();
        roles.put("mains",    new ArrayList<>());
        roles.put("sides",    new ArrayList<>());
        roles.put("drinks",   new ArrayList<>());
        roles.put("desserts", new ArrayList<>());
        roles.put("snacks",   new ArrayList<>());

        for (MenuItem item : items) {
            String cat  = catName(item);
            String name = itemName(item);
            if (anyMatch(cat, "drink", "juice", "water", "beverage", "cocktail", "smoothie") ||
                anyMatch(name, "juice", "water", "drink", "soda", "tea", "coffee", "smoothie", "milk", "zobo", "kunu")) {
                roles.get("drinks").add(item);
            } else if (anyMatch(cat, "dessert", "sweet", "cake", "pastry", "ice cream") ||
                       anyMatch(name, "cake", "dessert", "sweet", "ice cream", "pudding", "donut", "doughnut")) {
                roles.get("desserts").add(item);
            } else if (anyMatch(cat, "snack", "appetizer", "starter", "light bites") ||
                       anyMatch(name, "snack", "appetizer", "spring roll", "samosa", "chips", "puff puff")) {
                roles.get("snacks").add(item);
            } else if (anyMatch(cat, "side", "salad", "soup", "sauce", "accompaniment") ||
                       anyMatch(name, "salad", "soup", "coleslaw", "side", "bread", "plantain", "moi moi", "sauce")) {
                roles.get("sides").add(item);
            } else {
                roles.get("mains").add(item);
            }
        }
        return roles;
    }

    // ── Combo builders ─────────────────────────────────────────────────────────────

    private List<MenuDtos.ComboSuggestion> buildMainSideDrinkCombos(
            Map<String, List<MenuItem>> byRole,
            MenuDtos.FoodSuggestionRequest req,
            int people, int limit
    ) {
        List<MenuItem> mains  = sortedByHealth(byRole.getOrDefault("mains", List.of()));
        List<MenuItem> sides  = sortedByHealth(byRole.getOrDefault("sides", List.of()));
        List<MenuItem> drinks = sortedByHealth(byRole.getOrDefault("drinks", List.of()));

        if (mains.isEmpty()) return List.of();

        List<MenuDtos.ComboSuggestion> combos = new ArrayList<>();
        int built = 0;

        for (int mi = 0; mi < mains.size() && built < Math.min(limit, 5); mi++) {
            MenuItem main  = mains.get(mi);
            MenuItem side  = sides.isEmpty()  ? null : sides.get(Math.min(mi, sides.size()  - 1));
            MenuItem drink = drinks.isEmpty() ? null : drinks.get(Math.min(mi, drinks.size() - 1));

            List<MenuItem> comboItems = new ArrayList<>();
            comboItems.add(main);
            if (side  != null) comboItems.add(side);
            if (drink != null) comboItems.add(drink);

            BigDecimal total = sumPrices(comboItems);

            if (exceedsBudget(req, total)) continue;

            combos.add(buildCombo(comboName(req, "Balanced Meal Combo", built), comboItems, total, req, people));
            built++;
        }
        return combos;
    }

    private List<MenuDtos.ComboSuggestion> buildLightCombos(
            Map<String, List<MenuItem>> byRole,
            MenuDtos.FoodSuggestionRequest req,
            int people, int limit
    ) {
        List<MenuItem> snacks  = sortedByHealth(byRole.getOrDefault("snacks", List.of()));
        List<MenuItem> drinks  = sortedByHealth(byRole.getOrDefault("drinks", List.of()));
        List<MenuItem> desserts = sortedByHealth(byRole.getOrDefault("desserts", List.of()));

        List<MenuDtos.ComboSuggestion> combos = new ArrayList<>();

        if (!snacks.isEmpty() && !drinks.isEmpty()) {
            List<MenuItem> items = List.of(snacks.get(0), drinks.get(0));
            BigDecimal total = sumPrices(items);
            if (!exceedsBudget(req, total)) {
                combos.add(buildCombo("Light Snack Combo", items, total, req, people));
            }
        }
        if (!desserts.isEmpty() && !drinks.isEmpty()) {
            List<MenuItem> items = List.of(desserts.get(0), drinks.get(0));
            BigDecimal total = sumPrices(items);
            if (!exceedsBudget(req, total)) {
                combos.add(buildCombo("Sweet Treat Combo", items, total, req, people));
            }
        }
        return combos;
    }

    private List<MenuDtos.ComboSuggestion> buildSingleItemFallbacks(
            List<MenuItem> pool,
            MenuDtos.FoodSuggestionRequest req,
            int people, int existing, int limit
    ) {
        int needed = limit - existing;
        if (needed <= 0) return List.of();

        return sortedByHealth(pool).stream()
                .filter(i -> !exceedsBudget(req, money(i.getBasePrice())))
                .limit(needed)
                .map(item -> {
                    BigDecimal price = money(item.getBasePrice());
                    int score = nutritionScoringService.scoreItem(item);
                    List<String> tags = nutritionScoringService.tagsForCombo(List.of(item), score);
                    return new MenuDtos.ComboSuggestion(
                            item.getName(),
                            List.of(new MenuDtos.ComboItem(item.getId(), item.getName(), price)),
                            price,
                            score,
                            tags,
                            "A well-rated option that suits your meal preferences.",
                            0.70
                    );
                })
                .collect(Collectors.toList());
    }

    // ── Combo assembly ────────────────────────────────────────────────────────────

    private MenuDtos.ComboSuggestion buildCombo(
            String name, List<MenuItem> items, BigDecimal total,
            MenuDtos.FoodSuggestionRequest req, int people
    ) {
        int score = nutritionScoringService.scoreCombo(items);
        List<String> tags = nutritionScoringService.tagsForCombo(items, score);
        double confidence = Math.min(0.95, 0.65 + score / 400.0);
        return new MenuDtos.ComboSuggestion(
                name,
                items.stream()
                     .map(i -> new MenuDtos.ComboItem(i.getId(), i.getName(), money(i.getBasePrice())))
                     .collect(Collectors.toList()),
                total,
                score,
                tags,
                buildReason(items, score, total, req, people),
                confidence
        );
    }

    // ── Naming & messaging ────────────────────────────────────────────────────────

    private String comboName(MenuDtos.FoodSuggestionRequest req, String defaultName, int index) {
        if (req == null) return defaultName;
        String mt = req.mealType();
        String ap = req.appetite();
        int    pc = req.peopleCount() != null ? req.peopleCount() : 1;
        if ("breakfast".equalsIgnoreCase(mt)) return index == 0 ? "Healthy Breakfast Combo" : "Morning Favourite Combo";
        if ("dinner".equalsIgnoreCase(mt))    return index == 0 ? "Satisfying Dinner Combo"  : "Evening Meal Combo";
        if ("snack".equalsIgnoreCase(mt))     return "Light Snack Combo";
        if ("dessert".equalsIgnoreCase(mt))   return "Sweet Treat Combo";
        if (pc >= 4)                          return index == 0 ? "Family Feast Combo" : "Family Meal Combo";
        if ("light".equalsIgnoreCase(ap))     return "Light Meal Combo";
        return index == 0 ? "Balanced Meal Combo" : "Chef's Pick Combo";
    }

    private String buildReason(
            List<MenuItem> items, int health, BigDecimal total,
            MenuDtos.FoodSuggestionRequest req, int people
    ) {
        String lead = items.get(0).getName();
        StringBuilder sb = new StringBuilder(lead).append(" leads this combo");
        if (health >= 75) sb.append(", providing a nutritious and balanced meal");
        if (req != null && req.budget() != null && total.compareTo(req.budget()) <= 0) sb.append(" within your budget");
        if (people > 1) sb.append(", great for ").append(people).append(" people");
        sb.append(".");
        return sb.toString();
    }

    private String buildResultMessage(String mealType, String appetite, int people) {
        if ("breakfast".equalsIgnoreCase(mealType)) return "Here are smart breakfast combos picked for you.";
        if ("dinner".equalsIgnoreCase(mealType))    return "Enjoy these balanced dinner combinations.";
        if ("snack".equalsIgnoreCase(mealType))     return "Light and satisfying snack combos for you.";
        if (people > 1) return "Combo suggestions sized for your group.";
        return "Here are healthy combo recommendations tailored to your preferences.";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    private List<MenuItem> sortedByHealth(List<MenuItem> items) {
        return items.stream()
                .sorted(Comparator.comparingInt(nutritionScoringService::scoreItem).reversed())
                .collect(Collectors.toList());
    }

    private BigDecimal sumPrices(List<MenuItem> items) {
        return items.stream()
                .map(i -> i.getBasePrice() != null ? i.getBasePrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean exceedsBudget(MenuDtos.FoodSuggestionRequest req, BigDecimal total) {
        return req != null && req.budget() != null && req.budget().compareTo(BigDecimal.ZERO) > 0
               && total.compareTo(req.budget()) > 0;
    }

    private MenuDtos.AiRecommendationResponse fallbackEmpty(String message) {
        return new MenuDtos.AiRecommendationResponse(
                "RULE_BASED", true, message, true, List.of(), List.of(),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String catName(MenuItem item) {
        return item.getCategory() == null || item.getCategory().getName() == null
               ? "" : item.getCategory().getName().toLowerCase();
    }

    private String itemName(MenuItem item) {
        return item.getName() == null ? "" : item.getName().toLowerCase();
    }

    private boolean anyMatch(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }
}
