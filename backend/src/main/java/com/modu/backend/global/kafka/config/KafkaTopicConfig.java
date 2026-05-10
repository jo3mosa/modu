package com.modu.backend.global.kafka.config;

import com.modu.backend.global.kafka.constant.KafkaTopic;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.topic.partitions:3}")
    private int partitions;

    @Value("${app.kafka.topic.replicas:3}")
    private int replicas;

    @Bean
    public KafkaAdmin.NewTopics kafkaTopics() {
        return new KafkaAdmin.NewTopics(
            topic(KafkaTopic.NEWS_ARTICLE_PUBLISHED),
            topic(KafkaTopic.MARKET_SIGNAL_DETECTED),
            topic(KafkaTopic.AI_TRIGGER_REQUESTED),
            topic(KafkaTopic.AI_DECISION_GENERATED),
            topic(KafkaTopic.TRADE_ORDER_SUBMITTED),
            topic(KafkaTopic.TRADE_ORDER_EXECUTED)
        );
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
            .partitions(partitions)
            .replicas(replicas)
            .build();
    }
}
