package com.modu.backend.global.kafka.config;

import com.modu.backend.global.kafka.constant.KafkaTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS   = 3;

    @Bean
    public KafkaAdmin.NewTopics kafkaTopics() {
        return new KafkaAdmin.NewTopics(
            TopicBuilder.name(KafkaTopic.NEWS_ARTICLE_PUBLISHED)
                .partitions(PARTITIONS).replicas(REPLICAS).build(),

            TopicBuilder.name(KafkaTopic.MARKET_SIGNAL_DETECTED)
                .partitions(PARTITIONS).replicas(REPLICAS).build(),

            TopicBuilder.name(KafkaTopic.AI_TRIGGER_REQUESTED)
                .partitions(PARTITIONS).replicas(REPLICAS).build(),

            TopicBuilder.name(KafkaTopic.AI_DECISION_GENERATED)
                .partitions(PARTITIONS).replicas(REPLICAS).build(),

            TopicBuilder.name(KafkaTopic.TRADE_ORDER_SUBMITTED)
                .partitions(PARTITIONS).replicas(REPLICAS).build(),

            TopicBuilder.name(KafkaTopic.TRADE_ORDER_EXECUTED)
                .partitions(PARTITIONS).replicas(REPLICAS).build()
        );
    }
}
