package com.ticketing.persistence.impl.support;

import com.ticketing.enums.DeadlineTypeEnum;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Computes the partition key of the sparse {@code deadline-index} (implementation spec 6.2):
 * {@code <DeadlineTypeEnum>#<shard>} with {@code shard = floorMod(orderId.hashCode(), deadlineShards)}. Sharding
 * spreads the due orders of one deadline type over several GSI partitions to avoid a hot partition; the sweepers
 * query every shard.
 */
@Component
public class DeadlinePartitioner {

    private final int shards;

    /**
     * Creates the partitioner.
     *
     * @param tableNames table settings holding the number of shards
     */
    public DeadlinePartitioner(DynamoDbTableNames tableNames) {
        this.shards = tableNames.deadlineShards();
    }

    /**
     * Partition of an order for a deadline type.
     *
     * @param type    the deadline type
     * @param orderId the order identifier
     * @return {@code <type>#<shard>}
     */
    public String partitionOf(DeadlineTypeEnum type, String orderId) {
        Objects.requireNonNull(type, "type");
        return partition(type, Math.floorMod(Objects.requireNonNull(orderId, "orderId").hashCode(), shards));
    }

    /**
     * Every partition of a deadline type, in shard order.
     *
     * @param type the deadline type
     * @return {@code <type>#0 .. <type>#<shards - 1>}
     */
    public List<String> partitionsOf(DeadlineTypeEnum type) {
        Objects.requireNonNull(type, "type");
        return IntStream.range(0, shards).mapToObj(shard -> partition(type, shard)).toList();
    }

    private static String partition(DeadlineTypeEnum type, int shard) {
        return type.name() + "#" + shard;
    }
}
