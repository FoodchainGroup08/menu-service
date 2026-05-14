package com.microservices.menu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GeminiFoodSuggestionClient implements FoodSuggestionAiClient {

    private static final int MAX_MENU_ITEMS_SENT_TO_MODEL = 40;

    private final ObjectMapper objectMapper;
    private final NutritionScoringService nutritionScoringService;
    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;

    public GeminiFoodSuggestionClient(
            ObjectMapper objectMapper,
            NutritionScoringService nutritionScoringService,
            @Value("${app.ai.food-suggestions.enabled:true}") boolean enabled,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${app.ai.gemini.model:gemini-2.5-flash}") String model,
            @Value("${app.ai.gemini.timeout-ms:15000}") int timeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.nutritionScoringService = nutritionScoringService;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<MenuDtos.AiRecommendationResponse> suggestFood(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    ) {
        if (!enabled) {
            log.debug("Gemini food suggestions disabled");
            return Optional.empty();
        }
        if (!hasText(apiKey)) {
            log.warn("GEMINI_API_KEY is not set — skipping Gemini call");
            return Optional.empty();
        }

        Map<String, MenuItem> menuById = activeItems.stream()
                .filter(item -> hasText(item.getId()))
                .collect(Collectors.toMap(MenuItem::getId, item -> item, (l, r) -> l, LinkedHashMap::new));

        try {
            Map<String, Object> geminiRequest = buildGeminiRequest(request, activeItems, missingQuestions);
            byte[] rawBytes = restClient.post()
                    .uri("/models/" + model + ":generateContent?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(geminiRequest)
                    .retrieve()
                    .body(byte[].class);

            JsonNode response = (rawBytes == null || rawBytes.length == 0)
                    ? null : objectMapper.readTree(rawBytes);

            String content = response == null
                    ? "" : response.at("/candidates/0/content/parts/0/text").asText("");
            if (!hasText(content)) {
                log.warn("Gemini returned empty content");
                return Optional.empty();
            }

            AiComboResponse aiResponse = objectMapper.readValue(content, AiComboResponse.class);
            return Optional.of(toResponse(aiResponse, request, menuById, missingQuestions));
        } catch (Exception e) {
            log.warn("Gemini food suggestion failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Gemini request ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildGeminiRequest(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    ) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt()))));
        req.put("contents", List.of(
                Map.of("role", "user", "parts",
                       List.of(Map.of("text", userPrompt(request, activeItems, missingQuestions))))));
        req.put("generationConfig", Map.of(
                "temperature", 0.2,
                "responseMimeType", "application/json",
                "responseSchema", responseSchema()));
        return req;
    }

    private String systemPrompt() {
        return """
                You are FoodChain's AI food assistant.
                Your job is to guide customers with short questions when preference details are missing,
                then recommend healthy and balanced MEAL COMBOS from the restaurant menu.

                Rules:
                - If required preferences are missing, set readyForSuggestions=false and list the missing questions.
                - If enough preferences are provided, set readyForSuggestions=true and build combos using ONLY IDs from the provided menu_items list.
                - Each combo should include 2–4 complementary items (e.g. main + side + drink, or breakfast + juice).
                - Never invent menu item IDs, names, prices, restaurants, or branches.
                - Prioritise nutritional balance, budget fit, dietary compatibility, and popularity.
                - Assign wellnessTags only from: Balanced, High Protein, Low Sugar, High Fiber.
                - Keep reasons friendly, practical, and under 25 words.
                - Vary combo names to reflect the meal occasion (e.g. Healthy Breakfast Combo, Family Feast Combo).
                """;
    }

    private String userPrompt(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    ) {
        try {
            Map<String, Object> preferences = new LinkedHashMap<>();
            preferences.put("budget", request == null ? null : request.budget());
            preferences.put("mealType", request == null ? null : request.mealType());
            preferences.put("appetite", request == null ? null : request.appetite());
            preferences.put("dietaryPreferences", request == null ? List.of() : nullToList(request.dietaryPreferences()));
            preferences.put("peopleCount", request == null ? null : request.peopleCount());
            preferences.put("fulfillmentType", request == null ? null : request.fulfillmentType());
            preferences.put("branchName", request == null ? null : request.branchName());
            preferences.put("limit", request == null ? null : request.limit());

            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("customer_preferences", preferences);
            prompt.put("missing_questions", missingQuestions);
            prompt.put("menu_items", menuPayload(activeItems));
            return objectMapper.writeValueAsString(prompt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Gemini prompt", e);
        }
    }

    private List<Map<String, Object>> menuPayload(List<MenuItem> activeItems) {
        return activeItems.stream()
                .sorted(Comparator.comparing(MenuItem::getBasePrice, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_MENU_ITEMS_SENT_TO_MODEL)
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("name", item.getName());
                    row.put("description", item.getDescription());
                    row.put("category", item.getCategory() == null ? null : item.getCategory().getName());
                    row.put("price", money(item.getBasePrice()));
                    return row;
                })
                .toList();
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> comboItem = new LinkedHashMap<>();
        comboItem.put("type", "OBJECT");
        comboItem.put("properties", Map.of(
                "comboName", Map.of("type", "STRING"),
                "itemIds",   Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                "reason",    Map.of("type", "STRING"),
                "wellnessTags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
        ));
        comboItem.put("required", List.of("comboName", "itemIds", "reason", "wellnessTags"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", Map.of(
                "message",              Map.of("type", "STRING"),
                "readyForSuggestions",  Map.of("type", "BOOLEAN"),
                "questions",            Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                "suggestions",          Map.of("type", "ARRAY", "items", comboItem)
        ));
        schema.put("required", List.of("message", "readyForSuggestions", "questions", "suggestions"));
        return schema;
    }

    // ── Response mapping ───────────────────────────────────────────────────────────

    private MenuDtos.AiRecommendationResponse toResponse(
            AiComboResponse aiResponse,
            MenuDtos.FoodSuggestionRequest request,
            Map<String, MenuItem> menuById,
            List<String> missingQuestions
    ) {
        if (!aiResponse.readyForSuggestions()) {
            List<String> questions = aiResponse.questions().isEmpty() ? missingQuestions : aiResponse.questions();
            return new MenuDtos.AiRecommendationResponse(
                    "GEMINI", false,
                    hasText(aiResponse.message()) ? aiResponse.message()
                            : "A few quick answers will help me suggest the right meal.",
                    false, questions, List.of(),
                    money(BigDecimal.ZERO));
        }

        int limit   = request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 10));
        int people  = request.peopleCount() == null ? 1 : Math.max(1, request.peopleCount());

        List<MenuDtos.ComboSuggestion> combos = new ArrayList<>();

        for (AiComboSuggestion aiCombo : aiResponse.suggestions()) {
            if (combos.size() >= limit) break;

            List<MenuItem> resolvedItems = aiCombo.itemIds().stream()
                    .map(menuById::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (resolvedItems.isEmpty()) continue;

            BigDecimal total = resolvedItems.stream()
                    .map(i -> i.getBasePrice() != null ? i.getBasePrice() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            int healthScore = nutritionScoringService.scoreCombo(resolvedItems);

            List<String> tags = aiCombo.wellnessTags().isEmpty()
                    ? nutritionScoringService.tagsForCombo(resolvedItems, healthScore)
                    : aiCombo.wellnessTags();

            combos.add(new MenuDtos.ComboSuggestion(
                    hasText(aiCombo.comboName()) ? aiCombo.comboName() : "Chef's Combo",
                    resolvedItems.stream()
                            .map(i -> new MenuDtos.ComboItem(i.getId(), i.getName(), money(i.getBasePrice())))
                            .collect(Collectors.toList()),
                    total,
                    healthScore,
                    tags,
                    hasText(aiCombo.reason()) ? aiCombo.reason() : "AI-selected combination for your preferences.",
                    Math.min(0.95, 0.70 + healthScore / 500.0)
            ));
        }

        if (combos.isEmpty()) {
            return new MenuDtos.AiRecommendationResponse(
                    "GEMINI", false,
                    "I could not safely match the AI response to available menu items.",
                    true, List.of(), List.of(), money(BigDecimal.ZERO));
        }

        BigDecimal estimatedTotal = combos.get(0).totalPrice()
                .multiply(BigDecimal.valueOf(people))
                .setScale(2, RoundingMode.HALF_UP);

        return new MenuDtos.AiRecommendationResponse(
                "GEMINI", false,
                hasText(aiResponse.message()) ? aiResponse.message()
                        : "Here are AI-curated healthy combos from the available menu.",
                true, List.of(), combos, estimatedTotal);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private List<String> nullToList(List<String> list) {
        return list == null ? List.of() : list;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    // ── Internal DTO records ───────────────────────────────────────────────────────

    private record AiComboResponse(
            String message,
            boolean readyForSuggestions,
            List<String> questions,
            List<AiComboSuggestion> suggestions
    ) {
        private AiComboResponse {
            questions   = questions   == null ? List.of() : questions;
            suggestions = suggestions == null ? List.of() : suggestions;
        }
    }

    private record AiComboSuggestion(
            String comboName,
            List<String> itemIds,
            String reason,
            List<String> wellnessTags
    ) {
        private AiComboSuggestion {
            itemIds      = itemIds      == null ? List.of() : itemIds;
            wellnessTags = wellnessTags == null ? List.of() : wellnessTags;
        }
    }
}
