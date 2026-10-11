package com.ticketing.config.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.client.sdk.impl.SqsQueueNames;
import com.ticketing.config.properties.AwsProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SqsClientConfigTest {

    private final SqsClientConfig config = new SqsClientConfig();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void shouldUseTheDefaultQueueNames() {
        AwsProperties defaults = new Binder().bindOrCreate("aws", AwsProperties.class);

        assertThat(config.sqsQueueNames(defaults))
                .isEqualTo(new SqsQueueNames("ticketing-order-reservation", "ticketing-order-payment"));
    }

    @Test
    void shouldUseTheConfiguredQueueNames() {
        AwsProperties custom = new Binder(new MapConfigurationPropertySource(Map.of(
                "aws.sqs.order-reservation-queue", "dev-reservation",
                "aws.sqs.order-payment-queue", "dev-payment")))
                .bindOrCreate("aws", AwsProperties.class);

        assertThat(config.sqsQueueNames(custom)).isEqualTo(new SqsQueueNames("dev-reservation", "dev-payment"));
    }

    @Test
    void shouldRegisterTheQueueNamesBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, SqsClientConfig.class)
                .withPropertyValues("aws.sqs.order-payment-queue=custom-payment")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SqsQueueNames.class).orderPaymentQueue()).isEqualTo("custom-payment");
                });
    }
}
