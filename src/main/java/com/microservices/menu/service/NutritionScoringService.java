package com.microservices.menu.service;

import com.microservices.menu.entity.MenuItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class NutritionScoringService {

    public int scoreItem(MenuItem item) {
        String name = item.getName() == null ? "" : item.getName().toLowerCase();
        String cat  = item.getCategory() == null || item.getCategory().getName() == null
                      ? "" : item.getCategory().getName().toLowerCase();

        int score = 55;

        if (anyMatch(name, "salad", "vegetable", "veggie", "fruit", "soup", "grilled", "steamed", "baked", "boiled")) score += 20;
        if (anyMatch(cat,  "salad", "vegetable", "fruit", "healthy", "light", "soup"))                               score += 15;
        if (anyMatch(name, "chicken", "fish", "turkey", "egg", "beans", "lentil"))                                    score += 10;
        if (anyMatch(name, "oats", "brown rice", "whole grain", "fibre", "fiber"))                                    score += 12;
        if (anyMatch(name, "juice", "water", "smoothie", "herbal tea"))                                               score +=  8;
        if (anyMatch(cat,  "juice", "smoothie", "tea", "water"))                                                      score +=  8;

        if (anyMatch(name, "fried", "crispy", "deep fry", "deep-fry"))                                               score -= 18;
        if (anyMatch(cat,  "dessert", "sweet", "cake", "pastry"))                                                     score -= 15;
        if (anyMatch(name, "cake", "donut", "doughnut", "chocolate", "cookie", "waffle", "brownie", "ice cream"))    score -= 18;
        if (anyMatch(name, "burger", "pizza", "hot dog", "sausage"))                                                  score -= 12;
        if (anyMatch(name, "soda", "cola", "fizzy", "energy drink"))                                                  score -= 12;

        return clamp(score);
    }

    public int scoreCombo(List<MenuItem> items) {
        if (items == null || items.isEmpty()) return 50;
        int total = items.stream().mapToInt(this::scoreItem).sum();
        return clamp(total / items.size());
    }

    public List<String> tagsForCombo(List<MenuItem> items, int healthScore) {
        List<String> tags = new ArrayList<>();
        if (items == null || items.isEmpty()) return tags;

        boolean hasProtein = items.stream().anyMatch(i -> {
            String n = i.getName() == null ? "" : i.getName().toLowerCase();
            return anyMatch(n, "chicken", "beef", "fish", "egg", "protein", "turkey", "lamb", "beans", "lentil");
        });
        boolean hasFiber = items.stream().anyMatch(i -> {
            String n = i.getName() == null ? "" : i.getName().toLowerCase();
            return anyMatch(n, "salad", "vegetable", "fruit", "fiber", "fibre", "oats", "beans", "whole grain");
        });
        boolean isLowSugar = items.stream().noneMatch(i -> {
            String c = i.getCategory() == null || i.getCategory().getName() == null
                       ? "" : i.getCategory().getName().toLowerCase();
            String n = i.getName() == null ? "" : i.getName().toLowerCase();
            return anyMatch(c, "dessert", "sweet") || anyMatch(n, "cake", "sugar", "sweet", "donut", "chocolate");
        });

        if (hasFiber)   tags.add("High Fiber");
        if (hasProtein) tags.add("High Protein");
        if (isLowSugar) tags.add("Low Sugar");
        if (healthScore >= 70) tags.add("Balanced");

        return tags;
    }

    private int clamp(int score) {
        return Math.min(100, Math.max(10, score));
    }

    private boolean anyMatch(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
