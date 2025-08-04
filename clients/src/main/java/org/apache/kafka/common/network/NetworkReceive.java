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
package org.apache.kafka.common.network;

import org.apache.kafka.common.memory.MemoryPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ScatteringByteChannel;

/**
 * A size delimited Receive that consists of a 4 byte network-ordered size N followed by N bytes of content
 */
public class NetworkReceive implements Receive {

    public static final String UNKNOWN_SOURCE = "";
    public static final int UNLIMITED = -1;
    private static final Logger log = LoggerFactory.getLogger(NetworkReceive.class);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final String source;
    private final ByteBuffer size;
    private final int maxSize;
    private final MemoryPool memoryPool;
    private int requestedBufferSize = -1;
    private ByteBuffer buffer;


    public NetworkReceive(String source, ByteBuffer buffer) {
        this(UNLIMITED, source);
        this.buffer = buffer;
    }

    public NetworkReceive(String source) {
        this(UNLIMITED, source);
    }

    public NetworkReceive(int maxSize, String source) {
        this(maxSize, source, MemoryPool.NONE);
    }

    public NetworkReceive(int maxSize, String source, MemoryPool memoryPool) {
        this.source = source;
        // 此处分配的是 HealByteBuffer，也就是在堆里分配的
        this.size = ByteBuffer.allocate(4);
        this.buffer = null;
        this.maxSize = maxSize;
        this.memoryPool = memoryPool;
    }

    public NetworkReceive() {
        this(UNKNOWN_SOURCE);
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public boolean complete() {
        return !size.hasRemaining() && buffer != null && !buffer.hasRemaining();
    }

    public long readFrom(ScatteringByteChannel channel) throws IOException {
        int read = 0;
        // 主要是读取message的size，然后赋值给 requestedBufferSize
        if (size.hasRemaining()) {
            // 将 channel 里的数据读取到当前的 size 里，因为 size 的大小只有4个字节，所以实际只读取了message的大小。
            // 通过变量名也可窥见一二，size = message 的大小
            int bytesRead = channel.read(size);
            if (bytesRead < 0)
                throw new EOFException();
            read += bytesRead;
            // 由于初始 size 只有4个字节大小，所以此时如果是服务端接收到producer发的信息过来，大概率 size.hasRemaining() = false
            if (!size.hasRemaining()) {
                size.rewind();
                int receiveSize = size.getInt();
                if (receiveSize < 0)
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + ")");
                // server: maxSize = 100MB
                if (maxSize != UNLIMITED && receiveSize > maxSize)
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + " larger than " + maxSize + ")");
                // 按照此处代码的含义，也就是消息的前4个字节会放消息的长度，SendBuilder.buildSend 方法里可以看到逻辑，发送消息是 messageSize + messageData
                requestedBufferSize = receiveSize; //may be 0 for some payloads (SASL)
                if (receiveSize == 0) {
                    buffer = EMPTY_BUFFER;
                }
            }
        }
        // server：接受 producer 和 consumer 发送的消息，一定会走到该逻辑
        if (buffer == null && requestedBufferSize != -1) { //we know the size we want but havent been able to allocate it yet
            buffer = memoryPool.tryAllocate(requestedBufferSize);
            if (buffer == null)
                log.trace("Broker low on memory - could not allocate buffer of size {} for source {}", requestedBufferSize, source);
        }
        if (buffer != null) {
            // server：将client发的消息存到了buffer里
            int bytesRead = channel.read(buffer);
            if (bytesRead < 0)
                throw new EOFException();
            read += bytesRead;
        }

        return read;
    }

    @Override
    public boolean requiredMemoryAmountKnown() {
        return requestedBufferSize != -1;
    }

    @Override
    public boolean memoryAllocated() {
        return buffer != null;
    }


    @Override
    public void close() throws IOException {
        if (buffer != null && buffer != EMPTY_BUFFER) {
            memoryPool.release(buffer);
            buffer = null;
        }
    }

    public ByteBuffer payload() {
        return this.buffer;
    }

    public int bytesRead() {
        if (buffer == null)
            return size.position();
        return buffer.position() + size.position();
    }

    /**
     * Returns the total size of the receive including payload and size buffer
     * for use in metrics. This is consistent with {@link NetworkSend#size()}
     */
    public int size() {
        return payload().limit() + size.limit();
    }

}
