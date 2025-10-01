package com.saemaps.android.usbserial.usbserial;

import android.util.Log;

/**
 * 环形缓冲区实现
 * 用于处理串口数据包的完整性，确保按指定长度提取完整数据包
 * 
 * 特性：
 * - 线程安全
 * - 自动扩容
 * - 支持按指定长度提取数据包
 * - 支持数据包完整性检查
 * 
 * @author SAE Maps
 */
public class RingBuffer {
    private static final String TAG = "RingBuffer";

    // 默认缓冲区大小（4KB，足够处理多个45字节的数据包）
    private static final int DEFAULT_CAPACITY = 4096;

    // 缓冲区数据
    private byte[] buffer;
    private int capacity;
    private int head; // 写入位置
    private int tail; // 读取位置
    private int size; // 当前数据量

    // 同步锁
    private final Object lock = new Object();

    /**
     * 构造函数 - 使用默认容量
     */
    public RingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造函数 - 指定容量
     * 
     * @param capacity 缓冲区容量
     */
    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new byte[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;

        Log.d(TAG, "RingBuffer created with capacity: " + capacity);
    }

    /**
     * 写入数据到缓冲区
     * 
     * @param data   要写入的数据
     * @param offset 数据偏移量
     * @param length 数据长度
     * @return 实际写入的字节数
     */
    public int write(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0;
        }

        synchronized (lock) {
            // 检查是否需要扩容
            if (size + length > capacity) {
                expandBuffer(size + length);
            }

            int written = 0;
            while (written < length) {
                int available = capacity - size;
                if (available == 0) {
                    // 缓冲区已满，扩容
                    expandBuffer(capacity * 2);
                    available = capacity - size;
                }

                int toWrite = Math.min(length - written, available);
                int writeToEnd = Math.min(toWrite, capacity - head);

                // 写入到缓冲区末尾
                System.arraycopy(data, offset + written, buffer, head, writeToEnd);

                // 如果数据跨越缓冲区末尾，写入到开头
                if (writeToEnd < toWrite) {
                    System.arraycopy(data, offset + written + writeToEnd, buffer, 0, toWrite - writeToEnd);
                }

                head = (head + toWrite) % capacity;
                size += toWrite;
                written += toWrite;
            }

            Log.v(TAG, String.format("Written %d bytes, buffer size: %d/%d",
                    written, size, capacity));
            return written;
        }
    }

    /**
     * 写入数据到缓冲区（重载方法）
     * 
     * @param data 要写入的数据
     * @return 实际写入的字节数
     */
    public int write(byte[] data) {
        if (data == null) {
            return 0;
        }
        return write(data, 0, data.length);
    }

    /**
     * 检查是否有足够的数据包
     * 
     * @param packetSize 数据包大小
     * @return true 如果有足够的数据
     */
    public boolean hasCompletePacket(int packetSize) {
        synchronized (lock) {
            return size >= packetSize;
        }
    }

    /**
     * 检查是否有完整的可变长度数据包
     * 数据包格式：前2字节包头(0x0068) + 1字节包长度 + 1字节命令类型 + 数据内容
     * 
     * @return 完整数据包的长度，如果没有完整数据包则返回-1
     */
    public int hasCompleteVariablePacket() {
        synchronized (lock) {
            // 至少需要4字节才能判断包头和包长度
            if (size < 4) {
                return -1;
            }

            // 查找包头 0x0068
            int searchPos = 0;
            while (searchPos <= size - 4) {
                // 检查包头 (Big-Endian: 0x68 0x00)
                if (getByteAt(searchPos) == (byte) 0x68 &&
                        getByteAt(searchPos + 1) == (byte) 0x00) {

                    // 获取包长度字段（第3字节）
                    int packetDataLength = getByteAt(searchPos + 2) & 0xFF;
                    int totalPacketLength = packetDataLength + 3; // 包长度 + 3字节包头

                    // 检查是否有足够的数据
                    if (size - searchPos >= totalPacketLength) {
                        return totalPacketLength;
                    } else {
                        // 数据包不完整，等待更多数据
                        return -1;
                    }
                }
                searchPos++;
            }

            // 没有找到有效的包头
            return -1;
        }
    }

    /**
     * 读取可变长度的完整数据包
     * 
     * @return 完整数据包，如果没有完整数据包则返回null
     */
    public byte[] readVariablePacket() {
        synchronized (lock) {
            int packetLength = hasCompleteVariablePacket();
            if (packetLength <= 0) {
                return null;
            }

            // 查找包头位置
            int headerPos = findPacketHeader();
            if (headerPos == -1) {
                return null;
            }

            // 提取完整数据包
            byte[] packet = new byte[packetLength];
            int read = 0;

            while (read < packetLength) {
                int toRead = Math.min(packetLength - read, capacity - ((tail + headerPos + read) % capacity));

                // 从缓冲区读取数据
                System.arraycopy(buffer, (tail + headerPos + read) % capacity,
                        packet, read, toRead);

                read += toRead;
            }

            // 移除已处理的数据包
            removeProcessedData(headerPos + packetLength);

            Log.v(TAG, String.format("Read variable packet of %d bytes, remaining: %d/%d",
                    packetLength, size, capacity));

            return packet;
        }
    }

    /**
     * 获取指定位置的字节
     * 
     * @param offset 偏移量
     * @return 字节值
     */
    private byte getByteAt(int offset) {
        return buffer[(tail + offset) % capacity];
    }

    /**
     * 查找数据包包头位置
     * 修复字节序问题：存储方式 0x68 0x00 (大端序)，检测时应该查找 0x68 0x00
     * 
     * @return 包头位置，如果没找到返回-1
     */
    private int findPacketHeader() {
        for (int i = 0; i <= size - 4; i++) {
            if (getByteAt(i) == (byte) 0x68 && getByteAt(i + 1) == (byte) 0x00) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 移除已处理的数据
     * 
     * @param bytesToRemove 要移除的字节数
     */
    private void removeProcessedData(int bytesToRemove) {
        if (bytesToRemove <= 0 || bytesToRemove > size) {
            return;
        }

        tail = (tail + bytesToRemove) % capacity;
        size -= bytesToRemove;

        // 如果缓冲区为空，重置位置
        if (size == 0) {
            head = 0;
            tail = 0;
        }
    }

    /**
     * 读取指定长度的数据包
     * 
     * @param packetSize 数据包大小
     * @return 数据包字节数组，如果数据不足则返回null
     */
    public byte[] readPacket(int packetSize) {
        synchronized (lock) {
            if (size < packetSize) {
                Log.v(TAG, String.format("Insufficient data: need %d, have %d", packetSize, size));
                return null;
            }

            byte[] packet = new byte[packetSize];
            int read = 0;

            while (read < packetSize) {
                int toRead = Math.min(packetSize - read, capacity - tail);

                // 从缓冲区末尾读取
                System.arraycopy(buffer, tail, packet, read, toRead);

                // 如果数据跨越缓冲区末尾，从开头读取
                if (toRead < packetSize - read) {
                    System.arraycopy(buffer, 0, packet, read + toRead, packetSize - read - toRead);
                }

                tail = (tail + packetSize - read) % capacity;
                read = packetSize;
            }

            size -= packetSize;

            Log.v(TAG, String.format("Read packet of %d bytes, remaining: %d/%d",
                    packetSize, size, capacity));

            return packet;
        }
    }

    /**
     * 读取所有可用数据
     * 
     * @return 所有可用数据的字节数组
     */
    public byte[] readAll() {
        synchronized (lock) {
            if (size == 0) {
                return new byte[0];
            }

            byte[] data = new byte[size];
            int read = 0;

            while (read < size) {
                int toRead = Math.min(size - read, capacity - tail);

                System.arraycopy(buffer, tail, data, read, toRead);

                if (toRead < size - read) {
                    System.arraycopy(buffer, 0, data, read + toRead, size - read - toRead);
                }

                read += toRead;
            }

            tail = head;
            size = 0;

            Log.v(TAG, String.format("Read all %d bytes", data.length));
            return data;
        }
    }

    /**
     * 获取当前缓冲区中的数据量
     * 
     * @return 数据量
     */
    public int getSize() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * 获取缓冲区容量
     * 
     * @return 容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 检查缓冲区是否为空
     * 
     * @return true 如果为空
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * 检查缓冲区是否已满
     * 
     * @return true 如果已满
     */
    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    /**
     * 清空缓冲区
     */
    public void clear() {
        synchronized (lock) {
            head = 0;
            tail = 0;
            size = 0;
            Log.d(TAG, "Buffer cleared");
        }
    }

    /**
     * 扩容缓冲区
     * 
     * @param newCapacity 新容量
     */
    private void expandBuffer(int newCapacity) {
        byte[] newBuffer = new byte[newCapacity];

        if (size > 0) {
            if (head > tail) {
                // 数据连续
                System.arraycopy(buffer, tail, newBuffer, 0, size);
            } else {
                // 数据跨越缓冲区末尾
                int firstPart = capacity - tail;
                System.arraycopy(buffer, tail, newBuffer, 0, firstPart);
                System.arraycopy(buffer, 0, newBuffer, firstPart, size - firstPart);
            }
        }

        buffer = newBuffer;
        capacity = newCapacity;
        head = size;
        tail = 0;

        Log.d(TAG, String.format("Buffer expanded to %d bytes", newCapacity));
    }

    /**
     * 获取缓冲区状态信息（用于调试）
     * 
     * @return 状态字符串
     */
    public String getStatus() {
        synchronized (lock) {
            return String.format("RingBuffer[capacity=%d, size=%d, head=%d, tail=%d, free=%d]",
                    capacity, size, head, tail, capacity - size);
        }
    }
}
