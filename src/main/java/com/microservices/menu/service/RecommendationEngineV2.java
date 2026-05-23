package com.microservices.menu.service;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuItem;
import com.microservices.menu.entity.UserMenuInteraction;
import com.microservices.menu.repository.MenuItemRepository;
import com.microservices.menu.repository.UserMenuInteractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEngineV2 {

    private final MenuItemRepository menuItemRepository;
    private final UserMenuInteractionRepository interactionRepository;
    private final NutritionScoringService nutritionScoringService;

    public MenuDtos.AiRecommendationResponse recommend(
            String userId,
            MenuDtos.RecommendationRequestV2 request) {

        List<MenuItem> allActive = menuItemRepository.findByActiveTrue();
        if (allActive.isEmpty()) {
            return empty("No menu items are currently available.");
        }

        Map<String, UserMenuInteraction> historyByItemId = buildHistoryMap(userId);

        // Hard filter: budget + dietary restrictions
        List<MenuItem> pool = allActive.stream()
                .filter(item -> passesBudget(item, request))
                .filter(item -> passesDietary(item, safe(request.dietaryRestrictions())))
                .collect(Collectors.toList());

        if (pool.isEmpty()) pool = allActive; // relax filters rather than return empty

        // Score and sort
        List<MenuItem> sorted = pool.stream()
                .sorted(Comparator.comparingInt(
                        (MenuItem item) -> scoreItem(item, request, historyByItemId)).reversed())
                .collect(Collectors.toList());

        int limit  = request.limit()       != null ? Math.min(Math.max(1, request.limit()), 10) : 5;
        int people = request.peopleCount() != null ? Math.max(1, request.peopleCount())         : 1;

        List<MenuDtos.ComboSuggestion> combos = buildCombos(sorted, request, people, limit);

        if (combos.isEmpty()) {
            return empty("No suitable combinations found for your preferences. Try relaxing your filters.");
        }

        BigDecimal estimatedTotal = combos.get(0).totalPrice()
                .multiply(BigDecimal.valueOf(people))
                .setScale(2, RoundingMode.HALF_UP);

        return new MenuDtos.AiRecommendationResponse(
                "PREFERENCE_BASED_V2",
                false,
                buildMessage(request, people),
                true,
                List.of(),
                combos,
                estimatedTotal
        );
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private int scoreItem(
            MenuItem item,
            MenuDtos.RecommendationRequestV2 request,
            Map<String, UserMenuInteraction> historyByItemId) {

        int score = 50;

        // Cuisine preference (up to 35 pts)
        List<String> cuisines = safe(request.cuisinePreferences());
        if (!cuisines.isEmpty()) {
            boolean matches = cuisines.stream().anyMatch(c -> matchesCuisine(c, item));
            score += matches ? 35 : 0;
        } else {
            score += 15; // neutral bonus — no preference set
        }

        // Order history (up to 25 pts, recency penalty)
        UserMenuInteraction history = historyByItemId.get(item.getId());
        if (history != null) {
            score += Math.min(25, history.getOrderCount() * 5);
            if (history.getLastOrderedAt() != null &&
                history.getLastOrderedAt().isAfter(LocalDateTime.now().minusDays(3))) {
                score -= 10; // variety penalty for very recent orders
            }
        }

        // Spice level (up to 10 pts)
        String spice = request.spiceLevel();
        if (spice != null) {
            String name = itemName(item);
            boolean isSpicy = anyMatch(name, "spicy", "pepper", "hot", "suya", "chilli", "jerk");
            if ("spice_hot".equals(spice)    && isSpicy)   score += 10;
            if ("spice_mild".equals(spice)   && !isSpicy)  score += 10;
            if ("spice_medium".equals(spice))               score += 5;
        }

        // Mood match (up to 10 pts)
        List<String> moods = safe(request.moods());
        if (!moods.isEmpty()) {
            boolean moodHit = moods.stream().anyMatch(m -> matchesMood(m, item));
            score += moodHit ? 10 : 0;
        }

        // Appetite/meal type fit (up to 5 pts)
        score += appetiteScore(item, request.appetite(), request.mealType());

        // Nutrition bonus (0–5 pts)
        score += nutritionScoringService.scoreItem(item) / 20;

        return score;
    }

    // ── Hard filters ──────────────────────────────────────────────────────────

    private boolean passesBudget(MenuItem item, MenuDtos.RecommendationRequestV2 req) {
        if (Boolean.TRUE.equals(req.budgetUnlimited())) return true;
        if (req.budget() == null || req.budget().compareTo(BigDecimal.ZERO) <= 0) return true;
        return item.getBasePrice() == null || item.getBasePrice().compareTo(req.budget()) <= 0;
    }

    private boolean passesDietary(MenuItem item, List<String> restrictions) {
        if (restrictions.isEmpty() || restrictions.contains("no_restrictions")) return true;
        String name = itemName(item);
        for (String r : restrictions) {
            switch (r.toLowerCase()) {
                case "vegan", "vegetarian" -> {
                    if (anyMatch(name, "chicken", "beef", "fish", "pork", "meat",
                                       "shrimp", "prawn", "lamb", "goat", "turkey")) return false;
                }
                case "no_seafood" -> {
                    if (anyMatch(name, "fish", "shrimp", "prawn", "lobster", "crab",
                                       "seafood", "tilapia", "catfish", "salmon")) return false;
                }
                case "halal" -> {
                    if (anyMatch(name, "pork", "ham", "bacon", "wine", "beer", "alcohol")) return false;
                }
            }
        }
        return true;
    }

    // ── Cuisine matching ──────────────────────────────────────────────────────

    private boolean matchesCuisine(String cuisine, MenuItem item) {
        String name = itemName(item);
        String cat  = catName(item);
        return switch (cuisine.toLowerCase()) {
            case "nigerian"     -> anyMatch(name, "jollof", "egusi", "suya", "puff", "moi moi",
                                                   "pepper soup", "eba", "fufu", "akara", "amala",
                                                   "ofe", "banga", "ogbono", "zobo", "kunu") ||
                                    anyMatch(cat, "nigerian", "local");
            case "continental"  -> anyMatch(name, "pasta", "steak", "burger", "pizza", "salad",
                                                    "sandwich", "soup", "grill") ||
                                    anyMatch(cat, "continental", "western", "international");
            case "asian"        -> anyMatch(name, "rice", "noodle", "sushi", "ramen", "fried rice",
                                                    "stir fry", "dim sum", "curry") ||
                                    anyMatch(cat, "asian", "chinese", "thai");
            case "italian"      -> anyMatch(name, "pasta", "pizza", "risotto", "lasagna", "gnocchi") ||
                                    anyMatch(cat, "italian");
            case "fast_food"    -> anyMatch(name, "burger", "fries", "chicken", "wrap", "hotdog",
                                                    "nugget", "shawarma") ||
                                    anyMatch(cat, "fast food", "snack", "quick");
            default             -> cat.contains(cuisine.toLowerCase()) || name.contains(cuisine.toLowerCase());
        };
    }

    // ── Mood matching ─────────────────────────────────────────────────────────

    private boolean matchesMood(String mood, MenuItem item) {
        String name = itemName(item);
        String cat  = catName(item);
        return switch (mood.toLowerCase()) {
            case "spicy"        -> anyMatch(name, "spicy", "pepper", "hot", "suya", "jerk", "chilli");
            case "sweet"        -> anyMatch(name, "sweet", "cake", "dessert", "chocolate",
                                                    "candy", "fruit", "honey", "sugar") ||
                                    anyMatch(cat, "dessert", "sweet");
            case "savory"       -> anyMatch(name, "grilled", "smoked", "seasoned", "barbecue",
                                                    "roast", "baked") ||
                                    anyMatch(cat, "grill", "barbecue");
            case "comfort_food", "comfort food" ->
                                    anyMatch(name, "jollof", "rice", "stew", "soup", "pasta",
                                                    "burger", "pizza", "mac", "fries") ||
                                    anyMatch(cat, "comfort", "classic", "main");
            default             -> name.contains(mood.toLowerCase());
        };
    }

    // ── Appetite / meal type scoring ──────────────────────────────────────────

    private int appetiteScore(MenuItem item, String appetite, String mealType) {
        int score = 0;
        String name = itemName(item);
        String cat  = catName(item);
        BigDecimal price = item.getBasePrice() != null ? item.getBasePrice() : BigDecimal.ZERO;

        if ("light".equals(appetite)) {
            if (price.compareTo(new BigDecimal("5000")) <= 0) score += 3;
            if (anyMatch(cat, "snack", "light", "salad")) score += 2;
        } else if ("heavy".equals(appetite)) {
            if (price.compareTo(new BigDecimal("3000")) >= 0) score += 3;
            if (anyMatch(cat, "main", "grill", "combo")) score += 2;
        } else if ("moderate".equals(appetite)) {
            score += 3; // neutral
        }

        if (mealType != null) {
            switch (mealType.toLowerCase()) {
                case "breakfast" -> score += anyMatch(cat, "breakfast", "pastry") ||
                                              anyMatch(name, "egg", "toast", "oats", "pancake") ? 5 : 0;
                case "snack"     -> score += anyMatch(cat, "snack", "light") ? 5 : 0;
                case "dessert"   -> score += anyMatch(cat, "dessert", "sweet") ? 5 : 0;
                case "lunch", "dinner" ->
                                     score += anyMatch(cat, "main", "rice", "grill") ? 3 : 0;
            }
        }
        return score;
    }

    // ── Combo building ────────────────────────────────────────────────────────

    private List<MenuDtos.ComboSuggestion> buildCombos(
            List<MenuItem> sorted,
            MenuDtos.RecommendationRequestV2 request,
            int people, int limit) {

        Map<String, List<MenuItem>> byRole = groupByRole(sorted);

        List<MenuDtos.ComboSuggestion> combos = new ArrayList<>();
        List<MenuItem> mains   = byRole.getOrDefault("mains",    List.of());
        List<MenuItem> sides   = byRole.getOrDefault("sides",    List.of());
        List<MenuItem> drinks  = byRole.getOrDefault("drinks",   List.of());
        List<MenuItem> snacks  = byRole.getOrDefault("snacks",   List.of());
        List<MenuItem> desserts = byRole.getOrDefault("desserts", List.of());

        // Main + side + drink combos
        for (int i = 0; i < mains.size() && combos.size() < limit; i++) {
            MenuItem main  = mains.get(i);
            MenuItem side  = sides.isEmpty()  ? null : sides.get(i % sides.size());
            MenuItem drink = drinks.isEmpty() ? null : drinks.get(i % drinks.size());

            List<MenuItem> items = new ArrayList<>();
            items.add(main);
            if (side  != null) items.add(side);
            if (drink != null) items.add(drink);

            BigDecimal total = sumPrices(items);
            if (passesBudget(total, request)) {
                combos.add(toCombo(comboName(request, main), items, total, people));
            }
        }

        // Light combos for breakfast/snack
        String mealType = request.mealType();
        if (combos.size() < limit &&
            ("breakfast".equalsIgnoreCase(mealType) || "snack".equalsIgnoreCase(mealType))) {
            if (!snacks.isEmpty() && !drinks.isEmpty()) {
                List<MenuItem> items = List.of(snacks.get(0), drinks.get(0));
                BigDecimal total = sumPrices(items);
                if (passesBudget(total, request)) {
                    combos.add(toCombo("Light Snack Combo", items, total, people));
                }
            }
        }

        // Dessert + drink
        if (combos.size() < limit && "dessert".equalsIgnoreCase(mealType)) {
            if (!desserts.isEmpty() && !drinks.isEmpty()) {
                List<MenuItem> items = List.of(desserts.get(0), drinks.get(0));
                BigDecimal total = sumPrices(items);
                if (passesBudget(total, request)) {
                    combos.add(toCombo("Sweet Treat Combo", items, total, people));
                }
            }
        }

        // Single-item fallbacks if combos are still sparse
        if (combos.size() < limit) {
            Set<String> usedNames = combos.stream()
                    .map(MenuDtos.ComboSuggestion::comboName)
                    .collect(Collectors.toSet());
            sorted.stream()
                    .filter(i -> passesBudget(money(i.getBasePrice()), request))
                    .filter(i -> !usedNames.contains(comboName(request, i)))
                    .limit(limit - combos.size())
                    .forEach(item -> {
                        BigDecimal price = money(item.getBasePrice());
                        int score = nutritionScoringService.scoreItem(item);
                        combos.add(new MenuDtos.ComboSuggestion(
                                comboName(request, item),
                                List.of(new MenuDtos.ComboItem(item.getId(), item.getName(), price)),
                                price,
                                score,
                                nutritionScoringService.tagsForCombo(List.of(item), score),
                                "Recommended based on your preferences and order history.",
                                0.70
                        ));
                    });
        }

        return combos.stream().limit(limit).collect(Collectors.toList());
    }

    private MenuDtos.ComboSuggestion toCombo(
            String name, List<MenuItem> items, BigDecimal total, int people) {
        int score = nutritionScoringService.scoreCombo(items);
        List<String> tags = nutritionScoringService.tagsForCombo(items, score);
        String reason = items.get(0).getName() + " leads this combo" +
                (score >= 75 ? ", providing a nutritious and balanced meal" : "") +
                (people > 1 ? ", great for " + people + " people" : "") + ".";
        return new MenuDtos.ComboSuggestion(
                name,
                items.stream()
                     .map(i -> new MenuDtos.ComboItem(i.getId(), i.getName(), money(i.getBasePrice())))
                     .collect(Collectors.toList()),
                total,
                score,
                tags,
                reason,
                Math.min(0.95, 0.65 + score / 400.0)
        );
    }

    private Map<String, List<MenuItem>> groupByRole(List<MenuItem> items) {
        Map<String, List<MenuItem>> roles = new LinkedHashMap<>();
        roles.put("mains", new ArrayList<>());
        roles.put("sides", new ArrayList<>());
        roles.put("drinks", new ArrayList<>());
        roles.put("desserts", new ArrayList<>());
        roles.put("snacks", new ArrayList<>());
        for (MenuItem item : items) {
            String cat  = catName(item);
            String name = itemName(item);
            if (anyMatch(cat, "drink", "juice", "beverage") ||
                anyMatch(name, "juice", "water", "drink", "soda", "tea", "coffee", "smoothie", "zobo", "kunu")) {
                roles.get("drinks").add(item);
            } else if (anyMatch(cat, "dessert", "sweet", "cake") ||
                       anyMatch(name, "cake", "dessert", "ice cream", "donut", "doughnut", "pudding")) {
                roles.get("desserts").add(item);
            } else if (anyMatch(cat, "snack", "appetizer", "starter") ||
                       anyMatch(name, "snack", "appetizer", "spring roll", "samosa", "chips", "puff puff")) {
                roles.get("snacks").add(item);
            } else if (anyMatch(cat, "side", "salad", "soup", "sauce") ||
                       anyMatch(name, "salad", "soup", "coleslaw", "side", "plantain", "moi moi", "bread")) {
                roles.get("sides").add(item);
            } else {
                roles.get("mains").add(item);
            }
        }
        return roles;
    }

    private String comboName(MenuDtos.RecommendationRequestV2 req, MenuItem main) {
        String base = main.getName() == null ? "Special" :
                      main.getName().substring(0, 1).toUpperCase() + main.getName().substring(1);
        String mt = req.mealType();
        int    pc = req.peopleCount() != null ? req.peopleCount() : 1;
        if ("breakfast".equalsIgnoreCase(mt)) return base + " Breakfast";
        if ("dinner".equalsIgnoreCase(mt))    return base + " Dinner";
        if ("snack".equalsIgnoreCase(mt))     return base + " Snack";
        if ("dessert".equalsIgnoreCase(mt))   return base + " Treat";
        if (pc >= 4)                          return base + " Family Combo";
        return base + " Combo";
    }

    private String buildMessage(MenuDtos.RecommendationRequestV2 req, int people) {
        if ("breakfast".equalsIgnoreCase(req.mealType())) return "Here are personalised breakfast combos for you.";
        if ("dinner".equalsIgnoreCase(req.mealType()))    return "Enjoy these dinner combinations tailored to your taste.";
        if ("snack".equalsIgnoreCase(req.mealType()))     return "Light and satisfying snack combos picked for you.";
        if (people > 1) return "Group combo suggestions based on your preferences and order history.";
        return "Here are personalised combos based on your preferences and past orders.";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Map<String, UserMenuInteraction> buildHistoryMap(String userId) {
        if (userId == null) return Map.of();
        return interactionRepository.findByUserIdOrderByOrderCountDesc(userId)
                .stream()
                .collect(Collectors.toMap(
                        UserMenuInteraction::getMenuItemId,
                        i -> i,
                        (a, b) -> a));
    }

    private boolean passesBudget(BigDecimal total, MenuDtos.RecommendationRequestV2 req) {
        if (Boolean.TRUE.equals(req.budgetUnlimited())) return true;
        if (req.budget() == null || req.budget().compareTo(BigDecimal.ZERO) <= 0) return true;
        return total.compareTo(req.budget()) <= 0;
    }

    private BigDecimal sumPrices(List<MenuItem> items) {
        return items.stream()
                .map(i -> i.getBasePrice() != null ? i.getBasePrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
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

    private List<String> safe(List<String> list) {
        return list == null ? List.of() : list;
    }

    private MenuDtos.AiRecommendationResponse empty(String message) {
        return new MenuDtos.AiRecommendationResponse(
                "PREFERENCE_BASED_V2", false, message, true,
                List.of(), List.of(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }
}
