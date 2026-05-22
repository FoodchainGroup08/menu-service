package com.microservices.menu.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.menu.entity.UserMenuInteraction;
import com.microservices.menu.repository.UserMenuInteractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final UserMenuInteractionRepository interactionRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.received", groupId = "menu-service-history-group")
    @Transactional
    public void onOrderReceived(String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<>() {});

            String userId   = (String) event.get("customerId");
            String branchId = (String) event.get("branchId");
            if (userId == null || branchId == null) return;

            List<Map<String, Object>> items =
                    objectMapper.convertValue(event.get("items"), new TypeReference<>() {});
            if (items == null || items.isEmpty()) return;

            for (Map<String, Object> item : items) {
                String menuItemId   = (String) item.get("menuItemId");
                String menuItemName = (String) item.get("menuItemName");
                int    quantity     = item.get("quantity") instanceof Number n ? n.intValue() : 1;
                if (menuItemId == null) continue;

                int updated = interactionRepository.incrementOrderCount(
                        userId, menuItemId, branchId, quantity);

                if (updated == 0) {
                    interactionRepository.save(UserMenuInteraction.builder()
                            .userId(userId)
                            .menuItemId(menuItemId)
                            .menuItemName(menuItemName != null ? menuItemName : "")
                            .branchId(branchId)
                            .orderCount(quantity)
                            .firstOrderedAt(LocalDateTime.now())
                            .lastOrderedAt(LocalDateTime.now())
                            .build());
                }
            }
            log.debug("Updated history for userId={} branchId={} items={}", userId, branchId, items.size());
        } catch (Exception e) {
            log.warn("Failed to process order.received for history tracking: {}", e.getMessage());
        }
    }
}
