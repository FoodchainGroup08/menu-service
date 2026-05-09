package com.microservices.menu.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.menu.dtos.MenuDtos;
import com.microservices.menu.entity.MenuCategory;
import com.microservices.menu.entity.MenuItem;
import com.microservices.menu.repository.MenuCategoryRepository;
import com.microservices.menu.repository.MenuItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MenuServiceImpl implements MenuService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String ITEM_KEY_PREFIX = "menu:item:";
    private static final String CATEGORIES_KEY = "menu:categories";
    private static final String MENU_EVENTS_TOPIC = "menu-item-events";

    @Autowired private MenuItemRepository menuItemRepository;
    @Autowired private MenuCategoryRepository menuCategoryRepository;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    // ── Menu Items ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MenuDtos.MenuItemResponse createMenuItem(MenuDtos.CreateMenuItemRequest request) {
        MenuCategory category = resolveCategory(request.categoryId());
        MenuItem item = MenuItem.builder()
                .name(request.name())
                .description(request.description())
                .category(category)
                .basePrice(request.basePrice())
                .imageUrl(request.imageUrl())
                .build();
        MenuItem saved = menuItemRepository.save(item);
        log.info("MenuItem created id={}", saved.getId());
        publishEvent(saved, "CREATED");
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MenuDtos.MenuItemSummary> listMenuItems(String categoryId, Boolean active, Pageable pageable) {
        Page<MenuItem> page;
        if (categoryId != null && active != null) {
            page = menuItemRepository.findByCategory_IdAndActive(categoryId, active, pageable);
        } else if (categoryId != null) {
            page = menuItemRepository.findByCategory_Id(categoryId, pageable);
        } else if (active != null) {
            page = menuItemRepository.findByActive(active, pageable);
        } else {
            page = menuItemRepository.findAll(pageable);
        }
        return page.map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public MenuDtos.MenuItemResponse getMenuItem(String id) {
        String key = ITEM_KEY_PREFIX + id;
        String cached = getCached(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, MenuDtos.MenuItemResponse.class);
            } catch (Exception e) {
                log.warn("Cache deserialize failed for {}: {}", key, e.getMessage());
            }
        }
        MenuItem item = findItemOrThrow(id);
        MenuDtos.MenuItemResponse response = toResponse(item);
        putInCache(key, response);
        return response;
    }

    @Override
    @Transactional
    public MenuDtos.MenuItemResponse updateMenuItem(String id, MenuDtos.UpdateMenuItemRequest request) {
        MenuItem item = findItemOrThrow(id);
        if (request.name()        != null) item.setName(request.name());
        if (request.description() != null) item.setDescription(request.description());
        if (request.basePrice()   != null) item.setBasePrice(request.basePrice());
        if (request.imageUrl()    != null) item.setImageUrl(request.imageUrl());
        if (request.categoryId()  != null) item.setCategory(resolveCategory(request.categoryId()));
        MenuItem saved = menuItemRepository.save(item);
        log.info("MenuItem {} updated", id);
        evictCache(ITEM_KEY_PREFIX + id);
        publishEvent(saved, "UPDATED");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public MenuDtos.MenuItemResponse setItemActive(String id, boolean active) {
        MenuItem item = findItemOrThrow(id);
        item.setActive(active);
        MenuItem saved = menuItemRepository.save(item);
        log.info("MenuItem {} active={}", id, active);
        evictCache(ITEM_KEY_PREFIX + id);
        publishEvent(saved, active ? "ACTIVATED" : "DEACTIVATED");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public MenuDtos.MenuItemResponse toggleItemActive(String id) {
        MenuItem item = findItemOrThrow(id);
        item.setActive(!item.isActive());
        MenuItem saved = menuItemRepository.save(item);
        log.info("MenuItem {} toggled active={}", id, saved.isActive());
        evictCache(ITEM_KEY_PREFIX + id);
        publishEvent(saved, saved.isActive() ? "ACTIVATED" : "DEACTIVATED");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMenuItem(String id) {
        MenuItem item = findItemOrThrow(id);
        menuItemRepository.delete(item);
        log.info("MenuItem {} deleted", id);
        evictCache(ITEM_KEY_PREFIX + id);
        publishEvent(item, "DELETED");
    }

    // ── Categories ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MenuDtos.CategoryResponse createCategory(MenuDtos.CreateCategoryRequest request) {
        MenuCategory category = MenuCategory.builder()
                .name(request.name())
                .displayOrder(request.displayOrder())
                .build();
        MenuCategory saved = menuCategoryRepository.save(category);
        log.info("MenuCategory created id={}", saved.getId());
        evictCache(CATEGORIES_KEY);
        return toCategoryResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuDtos.CategoryResponse> listCategories() {
        String cached = getCached(CATEGORIES_KEY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<MenuDtos.CategoryResponse>>() {});
            } catch (Exception e) {
                log.warn("Cache deserialize failed for {}: {}", CATEGORIES_KEY, e.getMessage());
            }
        }
        List<MenuDtos.CategoryResponse> list = menuCategoryRepository.findByActiveTrueOrderByDisplayOrderAsc()
                .stream().map(this::toCategoryResponse).collect(Collectors.toList());
        putInCache(CATEGORIES_KEY, list);
        return list;
    }

    @Override
    @Transactional
    public MenuDtos.CategoryResponse updateCategory(String id, MenuDtos.UpdateCategoryRequest request) {
        MenuCategory category = findCategoryOrThrow(id);
        if (request.name()         != null) category.setName(request.name());
        if (request.displayOrder() != null) category.setDisplayOrder(request.displayOrder());
        MenuCategory saved = menuCategoryRepository.save(category);
        log.info("MenuCategory {} updated", id);
        evictCache(CATEGORIES_KEY);
        return toCategoryResponse(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MenuItem findItemOrThrow(String id) {
        return menuItemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found: " + id));
    }

    private MenuCategory findCategoryOrThrow(String id) {
        return menuCategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id));
    }

    private MenuCategory resolveCategory(String categoryId) {
        if (categoryId == null) return null;
        return findCategoryOrThrow(categoryId);
    }

    private void publishEvent(MenuItem item, String eventType) {
        try {
            MenuDtos.MenuItemEventPayload payload = new MenuDtos.MenuItemEventPayload(
                    item.getId(), item.getName(), item.getBasePrice(), item.isActive(), eventType);
            kafkaTemplate.send(MENU_EVENTS_TOPIC, item.getId(), objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to publish menu event for item {}: {}", item.getId(), e.getMessage());
        }
    }

    private String getCached(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis read failed [{}]: {}", key, e.getMessage());
            return null;
        }
    }

    private void putInCache(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed [{}]: {}", key, e.getMessage());
        }
    }

    private void evictCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis evict failed [{}]: {}", key, e.getMessage());
        }
    }

    private MenuDtos.MenuItemResponse toResponse(MenuItem item) {
        String catId   = item.getCategory() != null ? item.getCategory().getId()   : null;
        String catName = item.getCategory() != null ? item.getCategory().getName() : null;
        return new MenuDtos.MenuItemResponse(
                item.getId(), item.getName(), item.getDescription(),
                catId, catName, item.getBasePrice(), item.getImageUrl(),
                item.isActive(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private MenuDtos.MenuItemSummary toSummary(MenuItem item) {
        String catId   = item.getCategory() != null ? item.getCategory().getId()   : null;
        String catName = item.getCategory() != null ? item.getCategory().getName() : null;
        return new MenuDtos.MenuItemSummary(
                item.getId(), item.getName(), catId, catName, item.getBasePrice(), item.isActive());
    }

    private MenuDtos.CategoryResponse toCategoryResponse(MenuCategory c) {
        return new MenuDtos.CategoryResponse(c.getId(), c.getName(), c.getDisplayOrder(), c.isActive());
    }
}
