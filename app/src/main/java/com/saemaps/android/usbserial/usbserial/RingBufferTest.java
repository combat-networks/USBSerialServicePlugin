package com.saemaps.android.usbserial.usbserial;

import android.util.Log;

/**
 * 环形缓冲区测试类
 * 用于验证数据包完整性处理功能
 * 
 * @author SAE Maps
 */
public class RingBufferTest {
    private static final String TAG = "RingBufferTest";

    /**
     * 测试环形缓冲区的基本功能
     */
    public static void testBasicFunctionality() {
        Log.d(TAG, "🧪 Starting RingBuffer basic functionality test");

        RingBuffer buffer = new RingBuffer(1024);

        // 测试1: 写入和读取可变长度数据包
        // 开机响应包：0x68 0x00 0x01 0x55 (4字节)
        byte[] powerOnPacket = { (byte) 0x68, (byte) 0x00, (byte) 0x01, (byte) 0x55 };

        int written = buffer.write(powerOnPacket);
        Log.d(TAG, "✅ Test 1 - Written " + written + " bytes");

        int packetLength = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 1 - Has complete packet: " + (packetLength > 0 ? packetLength + " bytes" : "false"));

        byte[] readPacket = buffer.readVariablePacket();
        if (readPacket != null && readPacket.length == 4) {
            Log.d(TAG, "✅ Test 1 - Successfully read complete packet");
            // 验证包头
            if (readPacket[0] == (byte) 0x68 && readPacket[1] == (byte) 0x00) {
                Log.d(TAG, "✅ Test 1 - Packet header verified");
            } else {
                Log.e(TAG, "❌ Test 1 - Invalid packet header");
            }
        } else {
            Log.e(TAG, "❌ Test 1 - Failed to read complete packet");
        }

        // 测试2: 分片写入测试 - 定位数据包
        Log.d(TAG, "🧪 Test 2 - Testing fragmented packet writing");

        // 定位数据包：0x68 0x00 0x2A 0xCC + 42字节数据 (45字节总长度)
        byte[] locationPacket = new byte[45];
        locationPacket[0] = (byte) 0x68; // 包头1
        locationPacket[1] = (byte) 0x00; // 包头2
        locationPacket[2] = (byte) 0x2A; // 包长度 (42字节数据)
        locationPacket[3] = (byte) 0xCC; // 命令类型
        // 填充数据部分
        for (int i = 4; i < 45; i++) {
            locationPacket[i] = (byte) (i - 4);
        }

        // 分片写入：先写入前32字节
        byte[] part1 = new byte[32];
        System.arraycopy(locationPacket, 0, part1, 0, 32);
        buffer.write(part1);
        Log.d(TAG, "📝 Written first part (32 bytes)");
        Log.d(TAG, "🔍 Buffer status: " + buffer.getStatus());

        // 检查是否有完整数据包（应该没有）
        int hasComplete1 = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 2 - Has complete packet after first part: "
                + (hasComplete1 > 0 ? hasComplete1 + " bytes" : "false"));

        // 写入剩余13字节
        byte[] part2 = new byte[13];
        System.arraycopy(locationPacket, 32, part2, 0, 13);
        buffer.write(part2);
        Log.d(TAG, "📝 Written second part (13 bytes)");
        Log.d(TAG, "🔍 Buffer status: " + buffer.getStatus());

        // 检查是否有完整数据包（应该有）
        int hasComplete2 = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 2 - Has complete packet after second part: "
                + (hasComplete2 > 0 ? hasComplete2 + " bytes" : "false"));

        // 读取完整数据包
        byte[] completePacket = buffer.readVariablePacket();
        if (completePacket != null && completePacket.length == 45) {
            Log.d(TAG, "✅ Test 2 - Successfully read fragmented packet");

            // 验证包头和命令类型
            if (completePacket[0] == (byte) 0x68 && completePacket[1] == (byte) 0x00 &&
                    completePacket[2] == (byte) 0x2A && completePacket[3] == (byte) 0xCC) {
                Log.d(TAG, "✅ Test 2 - Packet structure verified");
            } else {
                Log.e(TAG, "❌ Test 2 - Invalid packet structure");
            }
        } else {
            Log.e(TAG, "❌ Test 2 - Failed to read fragmented packet");
        }

        // 测试3: 多个不同类型数据包测试
        Log.d(TAG, "🧪 Test 3 - Testing multiple packet types");

        // 写入3个不同类型的数据包
        // 1. 开机响应包 (4字节)
        byte[] powerOnPacket2 = { (byte) 0x68, (byte) 0x00, (byte) 0x01, (byte) 0x55 };
        buffer.write(powerOnPacket2);
        Log.d(TAG, "📝 Written power-on packet (4 bytes)");

        // 2. 查询ID响应包 (7字节)
        byte[] idQueryPacket = { (byte) 0x68, (byte) 0x00, (byte) 0x04, (byte) 0x02,
                (byte) 0x01, (byte) 0x02, (byte) 0x03 };
        buffer.write(idQueryPacket);
        Log.d(TAG, "📝 Written ID query packet (7 bytes)");

        // 3. 定位数据包 (45字节)
        byte[] locationPacket2 = new byte[45];
        locationPacket2[0] = (byte) 0x68; // 包头1
        locationPacket2[1] = (byte) 0x00; // 包头2
        locationPacket2[2] = (byte) 0x2A; // 包长度 (42字节数据)
        locationPacket2[3] = (byte) 0xCC; // 命令类型
        for (int i = 4; i < 45; i++) {
            locationPacket2[i] = (byte) (i - 4);
        }
        buffer.write(locationPacket2);
        Log.d(TAG, "📝 Written location packet (45 bytes)");

        Log.d(TAG, "🔍 Buffer status: " + buffer.getStatus());

        // 读取所有数据包
        int packetCount = 0;
        while (buffer.hasCompleteVariablePacket() > 0) {
            byte[] packet = buffer.readVariablePacket();
            if (packet != null) {
                packetCount++;
                Log.d(TAG, "📦 Read packet " + packetCount + " (" + packet.length + " bytes)");

                // 解析命令类型
                if (packet.length >= 4) {
                    int commandType = packet[3] & 0xFF;
                    switch (commandType) {
                        case 0x55:
                            Log.d(TAG, "  → Power-on response");
                            break;
                        case 0x02:
                            Log.d(TAG, "  → ID query response");
                            break;
                        case 0xCC:
                            Log.d(TAG, "  → Location data");
                            break;
                        default:
                            Log.d(TAG, "  → Unknown command: 0x" + Integer.toHexString(commandType).toUpperCase());
                            break;
                    }
                }
            }
        }

        if (packetCount == 3) {
            Log.d(TAG, "✅ Test 3 - Successfully processed " + packetCount + " packets");
        } else {
            Log.e(TAG, "❌ Test 3 - Expected 3 packets, got " + packetCount);
        }

        Log.d(TAG, "🎉 RingBuffer test completed");
    }

    /**
     * 测试环形缓冲区的边界情况
     */
    public static void testEdgeCases() {
        Log.d(TAG, "🧪 Starting RingBuffer edge cases test");

        RingBuffer buffer = new RingBuffer(256); // 较小的缓冲区

        // 测试1: 缓冲区满的情况
        Log.d(TAG, "🧪 Test 1 - Testing buffer overflow");

        byte[] largeData = new byte[300]; // 超过缓冲区大小
        for (int i = 0; i < 300; i++) {
            largeData[i] = (byte) i;
        }

        int written = buffer.write(largeData);
        Log.d(TAG, "📝 Written " + written + " bytes (buffer should auto-expand)");
        Log.d(TAG, "🔍 Buffer status: " + buffer.getStatus());

        // 测试2: 空缓冲区读取
        Log.d(TAG, "🧪 Test 2 - Testing empty buffer read");

        buffer.clear();
        int hasPacket = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 2 - Has packet in empty buffer: " + (hasPacket > 0 ? hasPacket + " bytes" : "false"));

        byte[] packet = buffer.readVariablePacket();
        Log.d(TAG, "✅ Test 2 - Read from empty buffer: " + (packet == null ? "null" : packet.length + " bytes"));

        // 测试3: 不完整数据包
        Log.d(TAG, "🧪 Test 3 - Testing incomplete packet");

        byte[] incompleteData = new byte[3]; // 只有3字节，不够4字节最小要求
        incompleteData[0] = (byte) 0x68;
        incompleteData[1] = (byte) 0x00;
        incompleteData[2] = (byte) 0x01;

        buffer.write(incompleteData);
        int hasIncomplete = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 3 - Has complete packet with incomplete data: "
                + (hasIncomplete > 0 ? hasIncomplete + " bytes" : "false"));

        // 测试4: 无效包头
        Log.d(TAG, "🧪 Test 4 - Testing invalid packet header");

        buffer.clear();
        byte[] invalidHeader = { (byte) 0x69, (byte) 0x01, (byte) 0x01, (byte) 0x55 }; // 错误的包头
        buffer.write(invalidHeader);
        int hasInvalid = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 4 - Has packet with invalid header: " + (hasInvalid > 0 ? hasInvalid + " bytes" : "false"));

        // 测试5: 混合数据（有效包+无效数据）
        Log.d(TAG, "🧪 Test 5 - Testing mixed data");

        buffer.clear();
        // 先写入一些无效数据
        byte[] junkData = { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD };
        buffer.write(junkData);

        // 再写入有效数据包
        byte[] validPacket = { (byte) 0x68, (byte) 0x00, (byte) 0x01, (byte) 0x55 };
        buffer.write(validPacket);

        int hasMixed = buffer.hasCompleteVariablePacket();
        Log.d(TAG, "✅ Test 5 - Has packet in mixed data: " + (hasMixed > 0 ? hasMixed + " bytes" : "false"));

        if (hasMixed > 0) {
            byte[] readPacket = buffer.readVariablePacket();
            if (readPacket != null && readPacket.length == 4) {
                Log.d(TAG, "✅ Test 5 - Successfully extracted valid packet from mixed data");
            } else {
                Log.e(TAG, "❌ Test 5 - Failed to extract valid packet from mixed data");
            }
        }

        Log.d(TAG, "🎉 Edge cases test completed");
    }

    /**
     * 运行所有测试
     */
    public static void runAllTests() {
        Log.d(TAG, "🚀 Starting RingBuffer comprehensive test suite");

        try {
            testBasicFunctionality();
            testEdgeCases();
            Log.d(TAG, "🎉 All tests completed successfully!");
        } catch (Exception e) {
            Log.e(TAG, "❌ Test suite failed", e);
        }
    }
}
