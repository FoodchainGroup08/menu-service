package com.microservices.menu.repository;

import com.microservices.menu.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, String> {
    List<MenuCategory> findByActiveTrueOrderByDisplayOrderAsc();
}
