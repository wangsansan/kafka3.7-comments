/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Built-in default partitioner.  Note, that this is just a utility class that is used directly from
 * RecordAccumulator, it does not implement the Partitioner interface.
 *
 * The class keeps track of various bookkeeping information required for adaptive sticky partitioning
 * (described in detail in KIP-794).  There is one partitioner object per topic.
 */
public class BuiltInPartitioner {
    private final Logger log;
    private final String topic;
    private final int stickyBatchSize;

    private volatile PartitionLoadStats partitionLoadStats = null;
    private final AtomicReference<StickyPartitionInfo> stickyPartitionInfo = new AtomicReference<>();

    // Visible and used for testing only.
    static volatile public Supplier<Integer> mockRandom = null;

    /**
     * BuiltInPartitioner constructor.
     *
     * @param topic The topic
     * @param stickyBatchSize How much to produce to partition before switch
     */
    public BuiltInPartitioner(LogContext logContext, String topic, int stickyBatchSize) {
        this.log = logContext.logger(BuiltInPartitioner.class);
        this.topic = topic;
        if (stickyBatchSize < 1) {
            throw new IllegalArgumentException("stickyBatchSize must be >= 1 but got " + stickyBatchSize);
        }
        this.stickyBatchSize = stickyBatchSize;
    }

    /**
     * Calculate the next partition for the topic based on the partition load stats.
     */
    private int nextPartition(Cluster cluster) {
        int random = mockRandom != null ? mockRandom.get() : Utils.toPositive(ThreadLocalRandom.current().nextInt());

        // Cache volatile variable in local variable.
        PartitionLoadStats partitionLoadStats = this.partitionLoadStats;
        int partition;
        /**
         * partitionLoadStats的数据更新操作发生在 Sender 线程死循环里，所以此时可能为null，也可能不为null
         */
        if (partitionLoadStats == null) {
            /**
             * 通过读 Cluster 的构造方法可知，所谓的 available partition 和 all partition 的区别在于
             * available partition 的leader != null，也就是可以发送和消费
             */
            // We don't have stats to do adaptive partitioning (or it's disabled), just switch to the next
            // partition based on uniform distribution.
            List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
            if (availablePartitions.size() > 0) {
                partition = availablePartitions.get(random % availablePartitions.size()).partition();
            } else {
                /**
                 * 此时可能会把消息发送到被设置了【暂停消费】的partition中
                 */
                // We don't have available partitions, just pick one among all partitions.
                List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
                partition = random % partitions.size();
            }
        } else {
            // Calculate next partition based on load distribution.
            // Note that partitions without leader are excluded from the partitionLoadStats.
            assert partitionLoadStats.length > 0;
            /**
             * 如果 cumulativeFrequencyTable = [4,5,8]
             * 随机数 0, 1, 2, 3 会映射到 partition[0], 4 会映射到 partition[1]
             * 而 5, 6, 7 会映射 partition[2]
             * cumulativeFrequencyTable：一个比较复杂的类似于分布式Hash的表，来决定应该选择哪个partition来发送消息
              */
            int[] cumulativeFrequencyTable = partitionLoadStats.cumulativeFrequencyTable;
            /**
             * cumulativeFrequencyTable[partitionLoadStats.length - 1]里保存的是最后一个partition对应的用来模运算的值
             * 也就是maxValue，通过随机数和maxValue进行模运算，得到一个value，来确定选哪个partition
             * 参考上面写的例子
             */
            int weightedRandom = random % cumulativeFrequencyTable[partitionLoadStats.length - 1];

            /**
             * 通过二分法，找出value应该映射到 cumulativeFrequencyTable 的哪个下标
             */
            // By construction, the cumulative frequency table is sorted, so we can use binary
            // search to find the desired index.
            int searchResult = Arrays.binarySearch(cumulativeFrequencyTable, 0, partitionLoadStats.length, weightedRandom);

            // binarySearch results the index of the found element, or -(insertion_point) - 1
            // (where insertion_point is the index of the first element greater than the key).
            // We need to get the index of the first value that is strictly greater, which
            // would be the insertion point, except if we found the element that's equal to
            // the searched value (in this case we need to get next).  For example, if we have
            //  4 5 8
            // and we're looking for 3, then we'd get the insertion_point = 0, and the function
            // would return -0 - 1 = -1, by adding 1 we'd get 0.  If we're looking for 4, we'd
            // get 0, and we need the next one, so adding 1 works here as well.
            int partitionIndex = Math.abs(searchResult + 1);
            assert partitionIndex < partitionLoadStats.length;
            partition = partitionLoadStats.partitionIds[partitionIndex];
        }

        log.trace("Switching to partition {} in topic {}", partition, topic);
        return partition;
    }

    /**
     * Test-only function.  When partition load stats are defined, return the end of range for the
     * random number.
     */
    public int loadStatsRangeEnd() {
        assert partitionLoadStats != null;
        assert partitionLoadStats.length > 0;
        return partitionLoadStats.cumulativeFrequencyTable[partitionLoadStats.length - 1];
    }

    /**
     * Peek currently chosen sticky partition.  This method works in conjunction with {@link #isPartitionChanged}
     * and {@link #updatePartitionInfo}.  The workflow is the following:
     *
     * 1. peekCurrentPartitionInfo is called to know which partition to lock.
     * 2. Lock partition's batch queue.
     * 3. isPartitionChanged under lock to make sure that nobody raced us.
     * 4. Append data to buffer.
     * 5. updatePartitionInfo to update produced bytes and maybe switch partition.
     *
     *  It's important that steps 3-5 are under partition's batch queue lock.
     *
     * @param cluster The cluster information (needed if there is no current partition)
     * @return sticky partition info object
     */
    StickyPartitionInfo peekCurrentPartitionInfo(Cluster cluster) {
        /**
         * 当不是第一次来获取该数据的时候，就会返回上次的 partition
         * 那是什么时候变更的呢？不可能同一个topic永远发一个partition吧？
         * 更新stickyPartitionInfo的逻辑在 BuiltInPartitioner#updatePartitionInfo()中
         * 每次对消息进行append前以及append后会做这个操作
         */
        StickyPartitionInfo partitionInfo = stickyPartitionInfo.get();
        if (partitionInfo != null) {
            return partitionInfo;
        }

        /**
         * 计算当前消息要发送到的 partition，且放到了 stickyPartitionInfo 中
         * stickyPartitionInfo 是 topic 维度的，所以每个topic该取值是一样的
         * 为了防止并发，使用CAS来更新 stickyPartitionInfo
         */
        // We're the first to create it.
        partitionInfo = new StickyPartitionInfo(nextPartition(cluster));
        if (stickyPartitionInfo.compareAndSet(null, partitionInfo)) {
            return partitionInfo;
        }

        // Someone has raced us.
        return stickyPartitionInfo.get();
    }

    /**
     * Check if partition is changed by a concurrent thread.  NOTE this function needs to be called under
     * the partition's batch queue lock.
     *
     * @param partitionInfo The sticky partition info object returned by peekCurrentPartitionInfo
     * @return true if sticky partition object is changed (race condition)
     */
    boolean isPartitionChanged(StickyPartitionInfo partitionInfo) {
        // partitionInfo may be null if the caller didn't use built-in partitioner.
        return partitionInfo != null && stickyPartitionInfo.get() != partitionInfo;
    }

    /**
     * Update partition info with the number of bytes appended and maybe switch partition.
     * NOTE this function needs to be called under the partition's batch queue lock.
     *
     * @param partitionInfo The sticky partition info object returned by peekCurrentPartitionInfo
     * @param appendedBytes The number of bytes appended to this partition
     * @param cluster The cluster information
     */
    void updatePartitionInfo(StickyPartitionInfo partitionInfo, int appendedBytes, Cluster cluster) {
        updatePartitionInfo(partitionInfo, appendedBytes, cluster, true);
    }

    /**
     * Update partition info with the number of bytes appended and maybe switch partition.
     * NOTE this function needs to be called under the partition's batch queue lock.
     *
     * @param partitionInfo The sticky partition info object returned by peekCurrentPartitionInfo
     * @param appendedBytes The number of bytes appended to this partition
     * @param cluster The cluster information
     * @param enableSwitch If true, switch partition once produced enough bytes
     */
    void updatePartitionInfo(StickyPartitionInfo partitionInfo, int appendedBytes, Cluster cluster, boolean enableSwitch) {
        // partitionInfo may be null if the caller didn't use built-in partitioner.
        if (partitionInfo == null)
            return;

        assert partitionInfo == stickyPartitionInfo.get();
        /**
         * partitionInfo.producedBytes是指当前topic使用的partition已经append的数据量
         */
        int producedBytes = partitionInfo.producedBytes.addAndGet(appendedBytes);

        // We're trying to switch partition once we produce stickyBatchSize bytes to a partition
        // but doing so may hinder batching because partition switch may happen while batch isn't
        // ready to send.  This situation is especially likely with high linger.ms setting.
        // Consider the following example:
        //   linger.ms=500, producer produces 12KB in 500ms, batch.size=16KB
        //     - first batch collects 12KB in 500ms, gets sent
        //     - second batch collects 4KB, then we switch partition, so 4KB gets eventually sent
        //     - ... and so on - we'd get 12KB and 4KB batches
        // To get more optimal batching and avoid 4KB fractional batches, the caller may disallow
        // partition switch if batch is not ready to send, so with the example above we'd avoid
        // fractional 4KB batches: in that case the scenario would look like this:
        //     - first batch collects 12KB in 500ms, gets sent
        //     - second batch collects 4KB, but partition switch doesn't happen because batch in not ready
        //     - second batch collects 12KB in 500ms, gets sent and now we switch partition.
        //     - ... and so on - we'd just send 12KB batches
        // We cap the produced bytes to not exceed 2x of the batch size to avoid pathological cases
        // (e.g. if we have a mix of keyed and unkeyed messages, key messages may create an
        // unready batch after the batch that disabled partition switch becomes ready).
        // As a result, with high latency.ms setting we end up switching partitions after producing
        // between stickyBatchSize and stickyBatchSize * 2 bytes, to better align with batch boundary.
        if (producedBytes >= stickyBatchSize * 2) {
            log.trace("Produced {} bytes, exceeding twice the batch size of {} bytes, with switching set to {}",
                producedBytes, stickyBatchSize, enableSwitch);
        }

        /**
         * stickyBatchSize 等于 batchSize = 16KB
         * 也就是
         * enableSwitch：true，当前partition发送了一个batch数据之后就换partition
         * enableSwitch: false， 当前partition发送了2个batch数据之后换partition
         */
        if (producedBytes >= stickyBatchSize && enableSwitch || producedBytes >= stickyBatchSize * 2) {
            // We've produced enough to this partition, switch to next.
            StickyPartitionInfo newPartitionInfo = new StickyPartitionInfo(nextPartition(cluster));
            stickyPartitionInfo.set(newPartitionInfo);
        }
    }

    /**
     * Update partition load stats from the queue sizes of each partition
     * NOTE: queueSizes are modified in place to avoid allocations
     *
     * @param queueSizes The queue sizes, partitions without leaders are excluded
     * @param partitionIds The partition ids for the queues, partitions without leaders are excluded
     * @param length The logical length of the arrays (could be less): we may eliminate some partitions
     *               based on latency, but to avoid reallocation of the arrays, we just decrement
     *               logical length
     * Visible for testing
     */
    public void updatePartitionLoadStats(int[] queueSizes, int[] partitionIds, int length) {
        if (queueSizes == null) {
            log.trace("No load stats for topic {}, not using adaptive", topic);
            partitionLoadStats = null;
            return;
        }
        assert queueSizes.length == partitionIds.length;
        assert length <= queueSizes.length;

        // The queueSizes.length represents the number of all partitions in the topic and if we have
        // less than 2 partitions, there is no need to do adaptive logic.
        // If partitioner.availability.timeout.ms != 0, then partitions that experience high latencies
        // (greater than partitioner.availability.timeout.ms) may be excluded, the length represents
        // partitions that are not excluded.  If some partitions were excluded, we'd still want to
        // go through adaptive logic, even if we have one partition.
        // See also RecordAccumulator#partitionReady where the queueSizes are built.
        if (length < 1 || queueSizes.length < 2) {
            log.trace("The number of partitions is too small: available={}, all={}, not using adaptive for topic {}",
                    length, queueSizes.length, topic);
            partitionLoadStats = null;
            return;
        }

        // We build cumulative frequency table from the queue sizes in place.  At the beginning
        // each entry contains queue size, then we invert it (so it represents the frequency)
        // and convert to a running sum.  Then a uniformly distributed random variable
        // in the range [0..last) would map to a partition with weighted probability.
        // Example: suppose we have 3 partitions with the corresponding queue sizes:
        //  0 3 1
        // Then we can invert them by subtracting the queue size from the max queue size + 1 = 4:
        //  4 1 3
        // Then we can convert it into a running sum (next value adds previous value):
        //  4 5 8
        // Now if we get a random number in the range [0..8) and find the first value that
        // is strictly greater than the number (e.g. for 4 it would be 5), then the index of
        // the value is the index of the partition we're looking for.  In this example
        // random numbers 0, 1, 2, 3 would map to partition[0], 4 would map to partition[1]
        // and 5, 6, 7 would map to partition[2].

        // Calculate max queue size + 1 and check if all sizes are the same.
        int maxSizePlus1 = queueSizes[0];
        boolean allEqual = true;
        for (int i = 1; i < length; i++) {
            if (queueSizes[i] != maxSizePlus1)
                allEqual = false;
            if (queueSizes[i] > maxSizePlus1)
                maxSizePlus1 = queueSizes[i];
        }
        ++maxSizePlus1;

        if (allEqual && length == queueSizes.length) {
            // No need to have complex probability logic when all queue sizes are the same,
            // and we didn't exclude partitions that experience high latencies (greater than
            // partitioner.availability.timeout.ms).
            log.trace("All queue lengths are the same, not using adaptive for topic {}", topic);
            partitionLoadStats = null;
            return;
        }

        // Invert and fold the queue size, so that they become separator values in the CFT.
        queueSizes[0] = maxSizePlus1 - queueSizes[0];
        for (int i = 1; i < length; i++) {
            queueSizes[i] = maxSizePlus1 - queueSizes[i] + queueSizes[i - 1];
        }
        log.trace("Partition load stats for topic {}: CFT={}, IDs={}, length={}",
                topic, queueSizes, partitionIds, length);
        partitionLoadStats = new PartitionLoadStats(queueSizes, partitionIds, length);
    }

    /**
     * Info for the current sticky partition.
     */
    public static class StickyPartitionInfo {
        private final int index;
        private final AtomicInteger producedBytes = new AtomicInteger();

        StickyPartitionInfo(int index) {
            this.index = index;
        }

        public int partition() {
            return index;
        }
    }

    /*
     * Default hashing function to choose a partition from the serialized key bytes
     */
    public static int partitionForKey(final byte[] serializedKey, final int numPartitions) {
        return Utils.toPositive(Utils.murmur2(serializedKey)) % numPartitions;
    }

    /**
     * The partition load stats for each topic that are used for adaptive partition distribution.
     */
    private final static class PartitionLoadStats {
        // 累计频率表
        public final int[] cumulativeFrequencyTable;
        public final int[] partitionIds;
        public final int length;
        public PartitionLoadStats(int[] cumulativeFrequencyTable, int[] partitionIds, int length) {
            assert cumulativeFrequencyTable.length == partitionIds.length;
            assert length <= cumulativeFrequencyTable.length;
            this.cumulativeFrequencyTable = cumulativeFrequencyTable;
            this.partitionIds = partitionIds;
            this.length = length;
        }
    }
}
