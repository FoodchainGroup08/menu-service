package com.microservices.menu.service;

import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuItem;

import java.util.List;
import java.util.Optional;

public interface FoodSuggestionAiClient {

    Optional<MenuDtos.FoodSuggestionResponse> suggestFood(
            MenuDtos.FoodSuggestionRequest request,
            List<MenuItem> activeItems,
            List<String> missingQuestions
    );
}
