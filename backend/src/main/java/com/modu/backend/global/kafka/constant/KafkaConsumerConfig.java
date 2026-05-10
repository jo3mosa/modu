package com.modu.backend.global.kafka.config;

import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer 설정
 * - 그룹별로 concurrency, maxPollRecords, maxPollIntervalMs를 다르게 설정
 * - 공통 설정은 baseProps()에서 관리하고 그룹별 Factory에서 오버라이드
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 모든 Consumer 그룹이 공통으로 사용하는 기본 설정
     */
    private Map<String, Object> baseProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);         // 수동 커밋 (처리 성공 후 커밋)
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");     // 재시작 시 놓친 메시지 재처리
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);         // 30초 내 응답 없으면 장애로 판단
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);      // 10초마다 브로커에 생존 신호
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.modu.*");         // 역직렬화 허용 패키지
        return props;
    }

    /**
     * 그룹별 Factory 생성 메서드
     * @param groupId           Consumer 그룹 ID
     * @param concurrency       병렬 처리 스레드 수
     * @param maxPollRecords    한 번에 가져올 최대 메시지 수
     * @param maxPollIntervalMs 메시지 처리 최대 허용 시간 (초과 시 리밸런싱)
     */
    private ConcurrentKafkaListenerContainerFactory<String, Object> factory(
            String groupId, int concurrency, int maxPollRecords, int maxPollIntervalMs) {

        Map<String, Object> props = baseProps(groupId);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);

        ConsumerFactory<String, Object> cf = new DefaultKafkaConsumerFactory<>(props);
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(concurrency);
        // 처리 성공 후 수동으로 offset 커밋 → 실패 시 재처리 보장
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // KIS API 속도 제한으로 concurrency=1 순차 처리
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kisOrderFactory() {
        return factory(KafkaConsumerGroup.KIS_ORDER, 1, 10, 30000);
    }

    // 체결 후 포트폴리오 업데이트 (병렬 처리)
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> portfolioUpdateFactory() {
        return factory(KafkaConsumerGroup.PORTFOLIO_UPDATE, 3, 50, 30000);
    }
}
