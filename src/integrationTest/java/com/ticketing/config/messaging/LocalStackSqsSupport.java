package com.ticketing.config.messaging;

import java.util.List;
import java.util.Map;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

final class LocalStackSqsSupport {
    static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:4.9");

    private LocalStackSqsSupport() {
    }

    record QueuePair(String url, String name, String dlqUrl) {
    }

    static LocalStackContainer localStack() {
        return new LocalStackContainer(LOCALSTACK_IMAGE).withServices("sqs");
    }

    static SqsAsyncClient client(LocalStackContainer localStack) {
        return SqsAsyncClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .build();
    }

    static QueuePair createQueueWithDeadLetterQueue(SqsAsyncClient sqs, String name, int maxReceiveCount,
                                                    int visibilityTimeoutSeconds) {
        String dlqUrl = sqs.createQueue(request -> request.queueName(name + "-dlq")).join().queueUrl();
        String dlqArn = sqs.getQueueAttributes(request -> request.queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .join().attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\""
                + maxReceiveCount + "\"}";
        String url = sqs.createQueue(request -> request.queueName(name).attributes(Map.of(
                        QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds),
                        QueueAttributeName.REDRIVE_POLICY, redrivePolicy)))
                .join().queueUrl();
        return new QueuePair(url, name, dlqUrl);
    }

    static void send(SqsAsyncClient sqs, String queueUrl, String body, String correlationId) {
        sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body).messageAttributes(Map.of(
                SqsListenerContainer.CORRELATION_ID_ATTRIBUTE,
                MessageAttributeValue.builder().dataType("String").stringValue(correlationId).build()))).join();
    }

    static int pendingMessages(SqsAsyncClient sqs, String queueUrl) {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .join().attributes();
        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    static List<Message> receive(SqsAsyncClient sqs, String queueUrl) {
        return sqs.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1))
                .join().messages();
    }
}
