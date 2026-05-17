package com.modu.backend.global.kafka.config;

import com.modu.backend.global.kafka.dto.AgentMessageKafkaMessage;
import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Kafka Consumer Factory 명시적 설정
 *
 * 토픽별로 동시성·페이로드 타입이 다르므로 ConcurrentKafkaListenerContainerFactory 를 각각 정의.
 * 본체 @KafkaListener 는 후속 이슈에서 추가:
 *  - kisOrderListenerContainerFactory       → KisOrderConsumer (306)
 *  - portfolioUpdateListenerContainerFactory → PortfolioUpdateConsumer (291)
 *  - aiDecisionListenerContainerFactory     → AiDecisionConsumer (263)
 *
 * 공통 설정(bootstrap-servers, auto-offset-reset 등)은 application.yml(spring.kafka.consumer.*) 그대로 사용.
 * AckMode 는 application.yml(spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE) 따라감.
 *
 * [동시성 정책]
 *  - KIS_ORDER         : 1 — KIS API 호출 직렬화 필수 (rate limit 대응)
 *  - PORTFOLIO_UPDATE  : 3 — DB·Redis 갱신 병렬 처리
 *  - AI_DECISION       : 3 — AI 판단 메시지 병렬 처리
 *  - AGENT_MESSAGE     : 3 — 단순 INSERT + SSE 푸시, 병렬 가능
 *
 * [maxPollRecords]
 *  - KIS_ORDER         : 10 — KIS 호출 latency 고려 작게
 *  - PORTFOLIO_UPDATE  : 50 — 빠른 DB 처리 가능
 *  - AI_DECISION       : 10 — 판단 처리 latency 고려
 *  - AGENT_MESSAGE     : 50 — 가벼운 작업이라 한 번에 다량 처리 가능
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    private final KafkaProperties kafkaProperties;

    public KafkaConsumerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeOrderMessage> kisOrderListenerContainerFactory() {
        return buildFactory(TradeOrderMessage.class, 1, 10);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeOrderExecutedMessage> portfolioUpdateListenerContainerFactory() {
        return buildFactory(TradeOrderExecutedMessage.class, 3, 50);
    }

    /**
     * AI 판단 메시지 Listener Factory (S14P31B106-263)
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AiDecisionMessage> aiDecisionListenerContainerFactory() {
        return buildFactory(AiDecisionMessage.class, 3, 10);
    }

    /**
     * AI 에이전트 발화 메시지 Listener Factory
     * 단순 INSERT + SSE 푸시라 동시성 3, 배치 50 으로 처리량 우선.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AgentMessageKafkaMessage> agentMessageListenerContainerFactory() {
        return buildFactory(AgentMessageKafkaMessage.class, 3, 50);
    }

    /**
     * 공통 Listener Container Factory 빌더
     *
     * application.yml 의 consumer 설정을 베이스로 하되, 다음 항목을 토픽별로 오버라이드:
     *  - max.poll.records (배치 크기)
     *  - concurrency (Listener 인스턴스 수)
     *  - JsonDeserializer 타입 바인딩 (헤더 미사용, com.modu.* 패키지 신뢰)
     */
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> buildFactory(
            Class<T> valueType, int concurrency, int maxPollRecords) {

        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(valueType, false);
        valueDeserializer.addTrustedPackages("com.modu.*");

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                valueDeserializer
        );

        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
