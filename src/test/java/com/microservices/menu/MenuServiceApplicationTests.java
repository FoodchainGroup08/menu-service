package com.microservices.menu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
class MenuServiceApplicationTests {

    @MockBean
    StringRedisTemplate redisTemplate;

    @MockBean
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    @Test
    void contextLoads() {
    }
}
