package com.microservices.menu.controller;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.service.RecommendationEngineV2;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v2/menu/recommendations")
@RequiredArgsConstructor
@Tag(name = "Recommendations v2", description = "Preference-based and behaviour-aware food recommendations. No AI — pure algorithm using saved preferences and order history.")
@SecurityRequirement(name = "Bearer Authentication")
public class RecommendationControllerV2 {

    private final RecommendationEngineV2 engine;

    @Operation(
        summary = "Get personalised food recommendations",
        description = """
            Returns ranked combo suggestions for the authenticated user based on:
            - Their saved food preferences (cuisine, dietary restrictions, spice level)
            - Their order history (items ordered frequently score higher)
            - The current request parameters (budget, appetite, mood, meal type)

            dietaryRestrictions: halal | vegan | vegetarian | no_seafood | low_carb | high_protein | diabetic_friendly | weight_loss
            cuisinePreferences:  nigerian | continental | asian | italian | fast_food
            spiceLevel:          spice_mild | spice_medium | spice_hot
            moods:               spicy | sweet | savory | comfort_food
            appetite:            light | moderate | heavy
            mealType:            breakfast | lunch | dinner | snack | dessert
            fulfillmentType:     pickup | delivery | dine-in
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Combo recommendations returned"),
        @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<MenuDtos.AiRecommendationResponse> recommend(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody MenuDtos.RecommendationRequestV2 request) {

        log.info("POST /v2/menu/recommendations userId={} branchId={}", userId, request.branchId());
        return ResponseEntity.ok(engine.recommend(userId, request));
    }
}
