package com.microservices.menu.repository;

import com.microservices.menu.entity.MenuItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {
    Page<MenuItem> findByActive(boolean active, Pageable pageable);
    Page<MenuItem> findByCategory_Id(String categoryId, Pageable pageable);
    Page<MenuItem> findByCategory_IdAndActive(String categoryId, boolean active, Pageable pageable);
    List<MenuItem> findByActiveTrue();
}
