package com.modu.backend.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient kakaoRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Bean
    public RestClient kisRestClient(@Value("${kis.api.url}") String kisBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .baseUrl(kisBaseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    log.warn("[KIS-WIRE] {} {} | headers={} | body={}",
                            request.getMethod(), request.getURI(),
                            request.getHeaders(),
                            new String(body, StandardCharsets.UTF_8));
                    return execution.execute(request, body);
                })
                .build();
    }
}
