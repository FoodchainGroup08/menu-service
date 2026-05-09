package com.microservices.menu.service;

import com.microservices.menu.dtos.MenuDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface MenuService {
    MenuDtos.MenuItemResponse createMenuItem(MenuDtos.CreateMenuItemRequest request);
    Page<MenuDtos.MenuItemSummary> listMenuItems(String categoryId, Boolean active, Pageable pageable);
    MenuDtos.MenuItemResponse getMenuItem(String id);
    MenuDtos.MenuItemResponse updateMenuItem(String id, MenuDtos.UpdateMenuItemRequest request);
    MenuDtos.MenuItemResponse setItemActive(String id, boolean active);
    MenuDtos.MenuItemResponse toggleItemActive(String id);
    void deleteMenuItem(String id);

    MenuDtos.CategoryResponse createCategory(MenuDtos.CreateCategoryRequest request);
    List<MenuDtos.CategoryResponse> listCategories();
    MenuDtos.CategoryResponse updateCategory(String id, MenuDtos.UpdateCategoryRequest request);
}
