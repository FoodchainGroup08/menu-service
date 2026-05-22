package com.microservices.menu.repository;

import com.microservices.menu.entity.UserMenuInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserMenuInteractionRepository extends JpaRepository<UserMenuInteraction, String> {

    List<UserMenuInteraction> findByUserIdOrderByOrderCountDesc(String userId);

    Optional<UserMenuInteraction> findByUserIdAndMenuItemIdAndBranchId(
            String userId, String menuItemId, String branchId);

    @Modifying
    @Query("""
           UPDATE UserMenuInteraction u
           SET u.orderCount = u.orderCount + :qty,
               u.lastOrderedAt = CURRENT_TIMESTAMP
           WHERE u.userId = :userId
             AND u.menuItemId = :menuItemId
             AND u.branchId = :branchId
           """)
    int incrementOrderCount(
            @Param("userId")     String userId,
            @Param("menuItemId") String menuItemId,
            @Param("branchId")   String branchId,
            @Param("qty")        int qty);
}
