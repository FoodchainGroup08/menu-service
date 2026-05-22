package com.microservices.menu.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_menu_interactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_item_branch",
        columnNames = {"user_id", "menu_item_id", "branch_id"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMenuInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "user_id", nullable = false, columnDefinition = "CHAR(36)")
    private String userId;

    @Column(name = "menu_item_id", nullable = false, columnDefinition = "CHAR(36)")
    private String menuItemId;

    @Column(name = "menu_item_name", nullable = false)
    private String menuItemName;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "branch_id", columnDefinition = "CHAR(36)")
    private String branchId;

    @Column(name = "order_count", nullable = false)
    @Builder.Default
    private int orderCount = 1;

    @Column(name = "last_ordered_at")
    private LocalDateTime lastOrderedAt;

    @Column(name = "first_ordered_at", updatable = false)
    private LocalDateTime firstOrderedAt;

    @PrePersist
    protected void onCreate() {
        firstOrderedAt = lastOrderedAt = LocalDateTime.now();
    }
}
