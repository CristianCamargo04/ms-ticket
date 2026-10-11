package com.ticketing.config.client;

import com.ticketing.client.sdk.impl.SqsQueueNames;
import com.ticketing.config.properties.AwsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of the SQS order queue client.
 */
@Configuration(proxyBeanMethods = false)
public class SqsClientConfig {

    /**
     * Names of the queues the order queue client publishes to ({@code aws.sqs.*}).
     *
     * @param properties AWS settings
     * @return the queue names
     */
    @Bean
    public SqsQueueNames sqsQueueNames(AwsProperties properties) {
        AwsProperties.Sqs sqs = properties.sqs();
        return new SqsQueueNames(sqs.orderReservationQueue(), sqs.orderPaymentQueue());
    }
}
