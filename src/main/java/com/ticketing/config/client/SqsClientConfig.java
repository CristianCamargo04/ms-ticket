package com.ticketing.config.client;

import com.ticketing.client.sdk.impl.SqsQueueNames;
import com.ticketing.config.properties.AwsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SqsClientConfig {
    @Bean
    public SqsQueueNames sqsQueueNames(AwsProperties properties) {
        AwsProperties.Sqs sqs = properties.sqs();
        return new SqsQueueNames(sqs.orderReservationQueue(), sqs.orderPaymentQueue());
    }
}
