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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GeminiFoodSuggestionClient implements FoodSuggestionAiClient {

    private static final int MAX_MENU_ITEMS_SENT_TO_MODEL = 40;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;

    public GeminiFoodSuggestionClient(
            ObjectMapper objectMapper,
            @Value("${app.ai.food-suggestions.enabled:false}") boolean enabled,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${app.ai.gemini.model:gemini-2.5-flash}") String model,
            @Value("${app.ai.gemini.timeout-ms:15000}") int timeoutMs
    ) {
        this.objectMapper = objectMapper;
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
    public Optional<MenuDtos.FoodSuggestionResponse> suggestFood(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    ) {
        if (!enabled) {
            log.debug("Gemini food suggestions disabled (app.ai.food-suggestions.enabled=false)");
            return Optional.empty();
        }
        if (!hasText(apiKey)) {
            log.warn("Gemini food suggestions enabled but GEMINI_API_KEY is not set — skipping AI call");
            return Optional.empty();
        }

        Map<String, MenuItem> menuById = activeItems.stream()
                .filter(item -> hasText(item.getId()))
                .collect(Collectors.toMap(MenuItem::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        try {
            Map<String, Object> geminiRequest = buildGeminiRequest(request, activeItems, missingQuestions);
            JsonNode response = restClient.post()
                    .uri("/models/" + model + ":generateContent?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(geminiRequest)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response == null
                    ? ""
                    : response.at("/candidates/0/content/parts/0/text").asText("");
            if (!hasText(content)) {
                log.warn("Gemini returned empty content");
                return Optional.empty();
            }

            AiFoodSuggestion aiResponse = objectMapper.readValue(content, AiFoodSuggestion.class);
            return Optional.of(toResponse(aiResponse, request, menuById, missingQuestions));
        } catch (Exception e) {
            log.warn("Gemini food suggestion failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildGeminiRequest(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    ) {
        Map<String, Object> geminiRequest = new LinkedHashMap<>();
        geminiRequest.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt()))
        ));
        geminiRequest.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt(request, activeItems, missingQuestions))))
        ));
        geminiRequest.put("generationConfig", Map.of(
                "temperature", 0.2,
                "responseMimeType", "application/json",
                "responseSchema", responseSchema()
        ));
        return geminiRequest;
    }

    private String systemPrompt() {
        return """
                You are FoodChain's food suggestion agent.
                Your job is to guide a customer with short questions when preference details are missing,
                then recommend suitable menu items from the restaurant menu.

                Rules:
                - If required preferences are missing, set readyForSuggestions=false and ask only those missing questions.
                - If enough preferences are provided, set readyForSuggestions=true and choose menu items only from the provided menu_items list.
                - Never invent menu item IDs, names, prices, restaurants, or branches.
                - Prefer items that fit budget, meal type, appetite, dietary preferences, people count, and fulfillment type.
                - Keep reasons friendly, practical, and under 22 words.
                - Optional add-ons should be short menu-combo ideas, not invented priced items.
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
            preferences.put("dietaryPreferences", request == null ? List.of() : nullToEmptyList(request.dietaryPreferences()));
            preferences.put("peopleCount", request == null ? null : request.peopleCount());
            preferences.put("fulfillmentType", request == null ? null : request.fulfillmentType());
            preferences.put("branchId", request == null ? null : request.branchId());
            preferences.put("branchName", request == null ? null : request.branchName());
            preferences.put("limit", request == null ? null : request.limit());

            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("customer_preferences", preferences);
            prompt.put("missing_questions", missingQuestions);
            prompt.put("menu_items", menuPayload(activeItems));

            return objectMapper.writeValueAsString(prompt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build AI suggestion prompt", e);
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
        Map<String, Object> suggestionItemSchema = new LinkedHashMap<>();
        suggestionItemSchema.put("type", "OBJECT");
        suggestionItemSchema.put("properties", Map.of(
                "menuItemId", Map.of("type", "STRING"),
                "reason", Map.of("type", "STRING"),
                "optionalAddOns", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
        ));
        suggestionItemSchema.put("required", List.of("menuItemId", "reason", "optionalAddOns"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", Map.of(
                "message", Map.of("type", "STRING"),
                "readyForSuggestions", Map.of("type", "BOOLEAN"),
                "questions", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                "suggestions", Map.of("type", "ARRAY", "items", suggestionItemSchema)
        ));
        schema.put("required", List.of("message", "readyForSuggestions", "questions", "suggestions"));
        return schema;
    }

    private MenuDtos.FoodSuggestionResponse toResponse(
            AiFoodSuggestion aiResponse,
            MenuDtos.FoodSuggestionRequest request,
            Map<String, MenuItem> menuById,
            List<String> missingQuestions
    ) {
        if (!aiResponse.readyForSuggestions()) {
            List<String> questions = aiResponse.questions().isEmpty()
                    ? missingQuestions
                    : aiResponse.questions();
            return new MenuDtos.FoodSuggestionResponse(
                    hasText(aiResponse.message()) ? aiResponse.message() : "A few quick answers will help me suggest the right meal.",
                    false,
                    questions,
                    List.of(),
                    money(BigDecimal.ZERO)
            );
        }

        int peopleCount = Math.max(1, request.peopleCount());
        int limit = request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 10));
        String branchName = hasText(request.branchName()) ? request.branchName().trim() : "Selected branch";

        List<MenuDtos.FoodSuggestionItem> suggestions = new ArrayList<>();
        for (AiFoodSuggestionItem aiItem : aiResponse.suggestions()) {
            MenuItem menuItem = menuById.get(aiItem.menuItemId());
            if (menuItem == null) {
                continue;
            }
            BigDecimal price = money(menuItem.getBasePrice());
            suggestions.add(new MenuDtos.FoodSuggestionItem(
                    menuItem.getId(),
                    menuItem.getName(),
                    price,
                    hasText(aiItem.reason()) ? aiItem.reason() : "AI-selected match for your preferences",
                    request.branchId(),
                    branchName,
                    price.multiply(BigDecimal.valueOf(peopleCount)).setScale(2, RoundingMode.HALF_UP),
                    aiItem.optionalAddOns().stream().filter(this::hasText).distinct().limit(2).toList()
            ));
            if (suggestions.size() >= limit) {
                break;
            }
        }

        if (suggestions.isEmpty()) {
            return new MenuDtos.FoodSuggestionResponse(
                    "I could not safely match the AI response to available menu items.",
                    true,
                    List.of(),
                    List.of(),
                    money(BigDecimal.ZERO)
            );
        }

        BigDecimal estimatedTotal = suggestions.stream()
                .map(MenuDtos.FoodSuggestionItem::estimatedTotalCost)
                .min(BigDecimal::compareTo)
                .orElse(money(BigDecimal.ZERO));

        return new MenuDtos.FoodSuggestionResponse(
                hasText(aiResponse.message()) ? aiResponse.message() : "Here are AI-picked options from the available menu.",
                true,
                List.of(),
                suggestions,
                estimatedTotal
        );
    }

    private List<String> nullToEmptyList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record AiFoodSuggestion(
            String message,
            boolean readyForSuggestions,
            List<String> questions,
            List<AiFoodSuggestionItem> suggestions
    ) {
        private AiFoodSuggestion {
            questions = questions == null ? List.of() : questions;
            suggestions = suggestions == null ? List.of() : suggestions;
        }
    }

    private record AiFoodSuggestionItem(
            String menuItemId,
            String reason,
            List<String> optionalAddOns
    ) {
        private AiFoodSuggestionItem {
            optionalAddOns = optionalAddOns == null ? List.of() : optionalAddOns;
        }
    }
}
