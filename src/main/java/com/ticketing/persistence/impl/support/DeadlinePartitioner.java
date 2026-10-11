package com.ticketing.persistence.impl.support;

import com.ticketing.enums.DeadlineTypeEnum;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
public class DeadlinePartitioner {
    private final int shards;

    public DeadlinePartitioner(DynamoDbTableNames tableNames) {
        this.shards = tableNames.deadlineShards();
    }

    public String partitionOf(DeadlineTypeEnum type, String orderId) {
        Objects.requireNonNull(type, "type");
        return partition(type, Math.floorMod(Objects.requireNonNull(orderId, "orderId").hashCode(), shards));
    }

    public List<String> partitionsOf(DeadlineTypeEnum type) {
        Objects.requireNonNull(type, "type");
        return IntStream.range(0, shards).mapToObj(shard -> partition(type, shard)).toList();
    }

    private static String partition(DeadlineTypeEnum type, int shard) {
        return type.name() + "#" + shard;
    }
}
