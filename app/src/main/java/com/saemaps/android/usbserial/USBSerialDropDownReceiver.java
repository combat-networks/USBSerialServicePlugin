package com.saemaps.android.usbserial;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.saemaps.android.dropdown.DropDown.OnStateListener;
import com.saemaps.android.dropdown.DropDownReceiver;
import com.saemaps.android.maps.MapView;
import com.saemaps.android.usbserial.USBSerialLifecycle;
import com.saemaps.android.usbserial.plugin.R;
import com.saemaps.android.usbserial.usbserial.USBSerialManager;

import java.util.List;

public class USBSerialDropDownReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "USBSerialDropDownReceiver";
    public static final String SHOW_PLUGIN = "com.saemaps.android.usbserial.SHOW_PLUGIN";

    private final View rootView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context pluginContext;

    private TextView tvStatus;
    private TextView tvDevices;
    private TextView tvDeviceId;
    private TextView tvLog;
    private ScrollView logScroll;
    private TextView tvPacketStats;

    // 使用现有的USB串口管理器
    private USBSerialManager usbSerialManager;
    private List<UsbDevice> detectedDevices = new ArrayList<>();

    // 数据包统计
    private int totalPackets = 0;
    private int powerOnPackets = 0;
    private int idQueryPackets = 0;
    private int locationPackets = 0;

    // 手台ID查询相关
    private long deviceId = -1; // 存储解析出的设备ID

    public USBSerialDropDownReceiver(MapView mapView, Context context) {
        super(mapView);
        Log.d(TAG, "USBSerialDropDownReceiver constructor called");
        this.pluginContext = context;

        try {
            Log.d(TAG, "About to inflate layout");
            rootView = PluginLayoutInflater.inflate(context, R.layout.usb_serial_layout, null);
            Log.d(TAG, "Layout inflated, rootView: " + (rootView != null ? "not null" : "null"));

            initViews();
            Log.d(TAG, "Views initialized successfully");

            // 🔧 修复：同步获取USB串口管理器实例并设置listener，确保listener在构造函数中完成设置
            try {
                Log.d(TAG, "Initializing USBSerialManager reference synchronously");
                usbSerialManager = USBSerialLifecycle.getUsbSerialManagerInstance();
                if (usbSerialManager == null) {
                    Log.e(TAG, "USBSerialManager instance not available from Lifecycle");
                } else {
                    usbSerialManager.setMapView(getMapView());
                    Log.d(TAG, "USBSerialManager instance obtained successfully");

                    // 🔧 立即设置listener，确保数据接收回调能正常工作
                    usbSerialManager.setListener(new USBSerialManager.USBSerialListener() {
                        @Override
                        public void onDeviceDetected(List<UsbDevice> devices) {
                            detectedDevices.clear();
                            detectedDevices.addAll(devices);
                            mainHandler.post(() -> updateDeviceList(devices));
                        }

                        @Override
                        public void onDeviceConnected(UsbDevice device) {
                            mainHandler.post(() -> {
                                appendLog("✅ 设备已连接: " + device.getVendorId() + ":" + device.getProductId());
                                if (tvStatus != null) {
                                    tvStatus.setText(
                                            "Status: 已连接到 " + device.getVendorId() + ":" + device.getProductId());
                                }
                                resetPacketStats(); // 重置数据包统计
                            });
                        }

                        @Override
                        public void onDeviceDisconnected() {
                            mainHandler.post(() -> {
                                appendLog("❌ 设备已断开连接");
                                if (tvStatus != null) {
                                    tvStatus.setText("Status: 设备已断开");
                                }
                                // 重置手台ID显示
                                if (tvDeviceId != null) {
                                    tvDeviceId.setText("手台ID: 未查询");
                                }
                                deviceId = -1;
                            });
                        }

                        @Override
                        public void onDataReceived(byte[] data) {
                            Log.d(TAG, "🎯 onDataReceived called with " + data.length + " bytes");
                            mainHandler.post(() -> {
                                Log.d(TAG, "🎯 onDataReceived UI update started");
                                Log.d(TAG, "🎯 tvLog is null: " + (tvLog == null));
                                Log.d(TAG, "🎯 logScroll is null: " + (logScroll == null));

                                // 🎯 增强：解析和格式化显示数据包
                                String packetInfo = formatPacketDisplay(data);
                                Log.d(TAG, "🎯 About to append log: " + packetInfo);

                                if (tvLog != null) {
                                    appendLog(packetInfo);
                                    Log.d(TAG, "🎯 Log appended successfully");
                                } else {
                                    Log.e(TAG, "🎯 tvLog is null, cannot append log");
                                }

                                Log.d(TAG, "🎯 onDataReceived UI update completed");
                            });
                        }

                        @Override
                        public void onError(Exception error) {
                            mainHandler.post(() -> {
                                appendLog("❌ 错误: " + error.getMessage());
                                if (tvStatus != null) {
                                    tvStatus.setText("Status: 错误 - " + error.getMessage());
                                }
                            });
                        }

                        @Override
                        public void onPermissionDenied(UsbDevice device) {
                            mainHandler.post(() -> {
                                appendLog("❌ 权限被拒绝: " + device.getVendorId() + ":" + device.getProductId());
                                if (tvStatus != null) {
                                    tvStatus.setText("Status: 权限被拒绝");
                                }
                            });
                        }
                    });
                    Log.d(TAG, "USBSerialManager initialized successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing USBSerialManager", e);
                mainHandler.post(() -> {
                    appendLog("❌ USB管理器初始化失败: " + e.getMessage());
                    if (tvStatus != null) {
                        tvStatus.setText("Status: 初始化失败");
                    }
                });
            }

            Log.d(TAG, "USBSerialDropDownReceiver constructor completed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error in USBSerialDropDownReceiver constructor", e);
            throw new RuntimeException("Failed to initialize USBSerialDropDownReceiver", e);
        }
    }

    private void initViews() {
        Log.d(TAG, "initViews called");
        try {
            tvStatus = rootView.findViewById(R.id.tv_status);
            tvDevices = rootView.findViewById(R.id.tv_devices);
            tvDeviceId = rootView.findViewById(R.id.tv_device_id);
            tvLog = rootView.findViewById(R.id.tv_log);
            logScroll = rootView.findViewById(R.id.log_scroll);
            tvPacketStats = rootView.findViewById(R.id.tv_packet_stats);

            Log.d(TAG, "TextViews found - Status: " + (tvStatus != null ? "yes" : "no") +
                    ", Devices: " + (tvDevices != null ? "yes" : "no") +
                    ", Log: " + (tvLog != null ? "yes" : "no") +
                    ", Scroll: " + (logScroll != null ? "yes" : "no"));

            Button btnScan = rootView.findViewById(R.id.btn_scan);
            Button btnConnect = rootView.findViewById(R.id.btn_connect_first);
            Button btnDisconnect = rootView.findViewById(R.id.btn_disconnect);
            Button btnSend = rootView.findViewById(R.id.btn_send_test);
            Button btnClearLog = rootView.findViewById(R.id.btn_clear_log);
            Button btnResetStats = rootView.findViewById(R.id.btn_reset_stats);

            Log.d(TAG, "Buttons found - Scan: " + (btnScan != null ? "yes" : "no") +
                    ", Connect: " + (btnConnect != null ? "yes" : "no") +
                    ", Disconnect: " + (btnDisconnect != null ? "yes" : "no") +
                    ", Send: " + (btnSend != null ? "yes" : "no"));

            btnScan.setOnClickListener(v -> {
                appendLog("🔍 开始USB设备检测...");
                if (usbSerialManager != null) {
                    // 禁用调试模式，允许正常连接
                    usbSerialManager.setDebugMode(false, 0);
                    usbSerialManager.scanDevices();
                } else {
                    appendLog("❌ USB管理器尚未初始化完成，请稍后再试");
                }
            });

            btnConnect.setOnClickListener(v -> {
                if (usbSerialManager == null) {
                    appendLog("❌ USB管理器尚未初始化完成，请稍后再试");
                    return;
                }
                if (detectedDevices.isEmpty()) {
                    appendLog("❌ 没有检测到USB设备，请先点击扫描按钮");
                    return;
                }
                appendLog("🔐 步骤2: 开始连接USB设备...");
                // 启用调试模式，步骤2
                usbSerialManager.setDebugMode(true, 2);
                usbSerialManager.connectToDevice(detectedDevices.get(0));
            });

            btnDisconnect.setOnClickListener(v -> {
                if (usbSerialManager == null) {
                    appendLog("❌ USB管理器尚未初始化完成，请稍后再试");
                    return;
                }
                appendLog("🔌 步骤4: 断开USB设备连接...");
                // 启用调试模式，步骤4
                usbSerialManager.setDebugMode(true, 4);
                usbSerialManager.disconnect();
            });

            btnSend.setOnClickListener(v -> {
                if (usbSerialManager == null) {
                    appendLog("❌ USB管理器尚未初始化完成，请稍后再试");
                    return;
                }
                queryDeviceId();
            });

            // 清除日志按钮
            btnClearLog.setOnClickListener(v -> {
                if (tvLog != null) {
                    tvLog.setText("USB Serial Plugin Log\n");
                    appendLog("🧹 日志已清除");
                }
            });

            // 重置统计按钮
            btnResetStats.setOnClickListener(v -> {
                resetPacketStats();
                appendLog("📊 数据包统计已重置");
            });

            Log.d(TAG, "initViews completed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error in initViews", e);
            throw new RuntimeException("Failed to initialize views", e);
        }
    }

    @Override
    public void disposeImpl() {
        if (usbSerialManager != null) {
            usbSerialManager.destroy();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive called with intent: " + (intent != null ? intent.getAction() : "null"));

        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Received null intent or action");
            return;
        }

        if (SHOW_PLUGIN.equals(intent.getAction())) {
            Log.d(TAG, "SHOW_PLUGIN broadcast received, showing dropdown");

            try {
                if (!isClosed()) {
                    Log.d(TAG, "the dropdown is already open, unhiding it");
                    unhideDropDown();
                    return;
                }

                Log.d(TAG, "About to show dropdown with rootView: " + (rootView != null ? "not null" : "null"));
                showDropDown(rootView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);

                // Initialize UI with default values
                if (tvStatus != null) {
                    tvStatus.setText("Status: USB Serial Plugin loaded");
                }
                if (tvDevices != null) {
                    tvDevices.setText("USB Serial Plugin Interface\n(USB functionality temporarily disabled)");
                }
                appendLog("USB Serial Plugin interface loaded successfully");
                Log.d(TAG, "Dropdown shown successfully");

            } catch (Exception e) {
                Log.e(TAG, "Error showing dropdown", e);
                // Try to show error message if possible
                try {
                    if (tvStatus != null) {
                        tvStatus.setText("Status: Error - " + e.getMessage());
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error setting error message", ex);
                }
            }
        }
    }

    /**
     * 更新设备列表显示
     */
    private void updateDeviceList(List<UsbDevice> devices) {
        if (devices.isEmpty()) {
            if (tvDevices != null) {
                tvDevices.setText("未发现串口设备\n请检查:\n1. USB设备是否已连接\n2. 设备是否支持串口通信\n3. 驱动是否匹配");
            }
            appendLog("⚠️ 未发现任何串口设备");
        } else {
            StringBuilder deviceListText = new StringBuilder("发现串口设备:\n");
            for (int i = 0; i < devices.size(); i++) {
                UsbDevice device = devices.get(i);
                deviceListText.append(String.format("%d. VID:%04X PID:%04X\n",
                        i + 1, device.getVendorId(), device.getProductId()));
            }
            if (tvDevices != null) {
                tvDevices.setText(deviceListText.toString());
            }
            appendLog("✅ 步骤1验证成功: 发现 " + devices.size() + " 个串口设备");
        }
    }

    private void appendLog(String message) {
        // 🔧 同时输出到 logcat，方便调试和崩溃分析
        Log.i(TAG, "[UI_LOG] " + message);

        // 🔧 确保UI更新在主线程中进行 - 修复线程安全问题
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 当前在主线程，直接更新UI
            updateLogUI(message);
        } else {
            // 当前在后台线程，切换到主线程更新UI
            mainHandler.post(() -> updateLogUI(message));
        }
    }

    /**
     * 在主线程中更新日志UI
     */
    private void updateLogUI(String message) {
        // 添加空指针检查，防止崩溃
        if (tvLog == null) {
            Log.w(TAG, "tvLog is null, cannot append log: " + message);
            return;
        }

        try {
            String current = tvLog.getText().toString();
            if ("Waiting...".contentEquals(current) || current.isEmpty()) {
                tvLog.setText(message);
            } else {
                tvLog.append("\n" + message);
            }

            // 安全地滚动到底部
            if (logScroll != null) {
                logScroll.post(() -> {
                    try {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    } catch (Exception e) {
                        Log.w(TAG, "Error scrolling log view", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error appending log message", e);
        }
    }

    /**
     * 更新连接状态显示
     */
    private void updateConnectionStatus(boolean connected) {
        try {
            if (tvLog == null)
                return;

            String status = connected ? "✅ 已连接" : "❌ 未连接";
            appendLog("🔌 连接状态: " + status);

            // 可以在这里更新UI状态指示器
            Log.d(TAG, "Connection status updated: " + connected);
        } catch (Exception e) {
            Log.e(TAG, "Error updating connection status", e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X ", b));
        }
        return result.toString().trim();
    }

    /**
     * 🎯 增强：格式化数据包显示，包含解析信息
     */
    private String formatPacketDisplay(byte[] data) {
        StringBuilder display = new StringBuilder();

        // 基本信息
        display.append(String.format("📥 数据包 (%d字节): ", data.length));

        // 检查是否为有效的数据包格式
        if (data.length >= 4) {
            // 验证包头 - 修复字节序问题
            // 存储方式: 0x68 0x00 (大端序)，接收时应该强制转换为 0x0068
            int header = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
            if (header == 0x0068) {
                // 解析包信息
                int packetDataLength = data[2] & 0xFF;
                int commandType = data[3] & 0xFF;
                int totalLength = packetDataLength + 3;

                // 检查包长度是否匹配
                if (data.length == totalLength) {
                    // 更新统计
                    updatePacketStats(commandType, packetDataLength);

                    // 识别数据包类型
                    String packetType = identifyPacketType(commandType, packetDataLength);
                    display.append(String.format("\n  📦 类型: %s", packetType));
                    display.append(String.format("\n  📏 数据长度: %d字节", packetDataLength));
                    display.append(String.format("\n  🎯 命令: 0x%02X", commandType));

                    // 显示十六进制数据
                    display.append(String.format("\n  🔢 原始数据: %s", bytesToHex(data)));

                    // 根据类型显示额外信息
                    display.append(formatPacketDetails(data, commandType, packetDataLength));

                } else {
                    // 包长度不匹配
                    display.append(String.format("\n  ⚠️ 包长度不匹配: 期望%d字节，实际%d字节", totalLength, data.length));
                    display.append(String.format("\n  🔢 原始数据: %s", bytesToHex(data)));
                }
            } else {
                // 无效包头
                display.append(String.format("\n  ❌ 无效包头: 0x%04X (期望: 0x0068)", header));
                display.append(String.format("\n  🔢 原始数据: %s", bytesToHex(data)));
            }
        } else {
            // 数据太短
            display.append(String.format("\n  ❌ 数据包太短: %d字节 (最小4字节)", data.length));
            display.append(String.format("\n  🔢 原始数据: %s", bytesToHex(data)));
        }

        return display.toString();
    }

    /**
     * 识别数据包类型
     */
    private String identifyPacketType(int commandType, int dataLength) {
        switch (commandType) {
            case 0x55:
                if (dataLength == 1) {
                    return "🔋 开机响应包";
                }
                break;
            case 0x02:
                if (dataLength == 4) {
                    return "🆔 查询ID响应包";
                }
                break;
            case 0xCC:
                if (dataLength == 42) {
                    return "📍 定位数据包";
                }
                break;
            default:
                return String.format("❓ 未知类型 (0x%02X)", commandType);
        }
        return String.format("❓ 未知类型 (0x%02X, %d字节)", commandType, dataLength);
    }

    /**
     * 根据数据包类型格式化详细信息
     */
    private String formatPacketDetails(byte[] data, int commandType, int dataLength) {
        StringBuilder details = new StringBuilder();

        switch (commandType) {
            case 0x55: // 开机响应
                details.append("\n  ✅ 设备开机成功");
                // 开机后自动发送查询ID命令
                appendLog("🔋 检测到设备开机，自动查询手台ID...");
                mainHandler.postDelayed(() -> queryDeviceId(), 100); // 延迟100ms后查询
                break;

            case 0x02: // 查询ID响应
                if (data.length >= 7) {
                    // 提取设备ID (字节4-6)
                    byte[] deviceIdBytes = new byte[3];
                    System.arraycopy(data, 4, deviceIdBytes, 0, 3);
                    String idString = String.format("%02X%02X%02X",
                            deviceIdBytes[0] & 0xFF, deviceIdBytes[1] & 0xFF, deviceIdBytes[2] & 0xFF);
                    details.append(String.format("\n  🆔 设备ID: %s", idString));

                    // 调用ID响应处理方法
                    handleIdResponse(data);
                }
                break;

            case 0xCC: // 定位数据
                details.append("\n  📍 定位数据包");
                details.append(String.format("\n  📊 数据长度: %d字节", dataLength));
                if (data.length >= 45) {
                    // 显示前几个字节作为示例
                    StringBuilder sampleData = new StringBuilder();
                    for (int i = 4; i < Math.min(12, data.length); i++) {
                        sampleData.append(String.format("%02X ", data[i] & 0xFF));
                    }
                    details.append(String.format("\n  🔍 数据样本: %s...", sampleData.toString().trim()));
                }
                break;

            default:
                details.append(String.format("\n  ❓ 未知命令类型: 0x%02X", commandType));
                break;
        }

        return details.toString();
    }

    /**
     * 更新数据包统计
     */
    private void updatePacketStats(int commandType, int dataLength) {
        totalPackets++;

        switch (commandType) {
            case 0x55:
                if (dataLength == 1) {
                    powerOnPackets++;
                }
                break;
            case 0x02:
                if (dataLength == 4) {
                    idQueryPackets++;
                }
                break;
            case 0xCC:
                if (dataLength == 42) {
                    locationPackets++;
                }
                break;
        }

        // 更新UI统计显示
        if (tvPacketStats != null) {
            String statsText = String.format("总计: %d | 开机: %d | ID: %d | 定位: %d",
                    totalPackets, powerOnPackets, idQueryPackets, locationPackets);
            tvPacketStats.setText(statsText);
        }
    }

    /**
     * 重置数据包统计
     */
    private void resetPacketStats() {
        totalPackets = 0;
        powerOnPackets = 0;
        idQueryPackets = 0;
        locationPackets = 0;

        if (tvPacketStats != null) {
            tvPacketStats.setText("总计: 0 | 开机: 0 | ID: 0 | 定位: 0");
        }
    }

    /**
     * 查询手台ID
     * 发送查询ID数据包: 0x68 0x00 0x01 0x02
     */
    private void queryDeviceId() {
        try {
            if (usbSerialManager == null) {
                appendLog("❌ USB管理器尚未初始化完成，请稍后再试");
                return;
            }

            // 检查USB设备是否已连接
            if (!usbSerialManager.isConnected()) {
                appendLog("❌ USB设备未连接，请先连接设备");
                appendLog("💡 请按以下步骤操作：");
                appendLog("   1. 点击'扫描'按钮检测USB设备");
                appendLog("   2. 点击'连接'按钮连接设备");
                appendLog("   3. 连接成功后再次点击'查询ID'");
                return;
            }

            appendLog("🆔 准备发送查询手台ID命令...");

            // 构造查询ID数据包
            byte[] queryPacket = new byte[4];
            queryPacket[0] = (byte) 0x68; // 包头标识低字节
            queryPacket[1] = (byte) 0x00; // 包头标识高字节
            queryPacket[2] = (byte) 0x01; // 包长度（数据部分长度）
            queryPacket[3] = (byte) 0x02; // 命令类型：查询设备ID

            appendLog("📦 准备发送数据包: " + bytesToHex(queryPacket));

            // 🔧 在后台线程中执行发送操作，避免阻塞UI线程
            new Thread(() -> {
                try {
                    appendLog("🔍 步骤2: 检查USB连接状态...");

                    // 🔧 发送前检查连接状态
                    if (!usbSerialManager.isConnected()) {
                        mainHandler.post(() -> {
                            appendLog("❌ USB设备未连接，无法发送查询命令");
                        });
                        return;
                    }

                    mainHandler.post(() -> {
                        appendLog("✅ 步骤2验证成功: USB设备已连接");
                        appendLog("🚀 步骤3: 开始发送数据包...");
                    });

                    usbSerialManager.sendData(queryPacket);

                    mainHandler.post(() -> {
                        appendLog("✅ 步骤3验证成功: 查询ID命令发送成功");
                        appendLog("⏳ 等待设备响应...");
                    });
                } catch (IOException e) {
                    mainHandler.post(() -> {
                        appendLog("❌ 步骤3失败: 发送失败 - " + e.getMessage());
                        appendLog("🔍 错误详情: " + e.getClass().getSimpleName());
                    });
                    Log.e(TAG, "Send data failed", e);

                    // 🔧 检查是否是连接问题
                    if (e.getMessage().contains("not connected") ||
                            e.getMessage().contains("connection lost") ||
                            e.getMessage().contains("disconnected")) {
                        mainHandler.post(() -> {
                            appendLog("⚠️ USB连接已断开，请重新连接设备");
                            // 更新UI状态
                            updateConnectionStatus(false);
                        });
                        return;
                    }

                    // 如果是超时错误，可以尝试重试
                    if (e.getMessage().contains("timeout") || e.getMessage().contains("Timeout")) {
                        mainHandler.post(() -> {
                            appendLog("⚠️ 发送超时，正在重试...");
                        });
                        try {
                            Thread.sleep(100); // 短暂等待
                            usbSerialManager.sendData(queryPacket);
                            mainHandler.post(() -> {
                                appendLog("✅ 重试发送成功");
                            });
                        } catch (Exception retryE) {
                            mainHandler.post(() -> {
                                appendLog("❌ 重试发送失败: " + retryE.getMessage());
                            });
                            Log.e(TAG, "Retry send failed", retryE);
                        }
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        appendLog("❌ 步骤3失败: 发送过程中发生未知错误");
                        appendLog("🔍 错误类型: " + e.getClass().getSimpleName());
                        appendLog("🔍 错误信息: " + e.getMessage());
                    });
                    Log.e(TAG, "Unexpected error during send", e);
                }
            }).start();

        } catch (Exception e) {
            appendLog("❌ 查询ID时发生未知错误: " + e.getMessage());
            Log.e(TAG, "Unexpected error in queryDeviceId", e);
        }
    }

    /**
     * 处理手台ID响应
     * 响应数据包格式: 0x68 0x00 0x04 0x02 ID1 ID2 ID3
     */
    private void handleIdResponse(byte[] data) {

        if (data.length >= 7) {
            // 解析设备ID (字节4-6) - 小端序解析
            long localId = (data[4] & 0xFF); // 最低字节
            localId |= ((data[5] & 0xFF) << 8); // 中间字节
            localId |= ((data[6] & 0xFF) << 16); // 最高字节

            deviceId = localId;

            appendLog("✅ 获取到设备ID成功！");
            appendLog("🆔 手台ID: " + localId);
            appendLog("📦 响应数据包: " + bytesToHex(data));
            appendLog("🔍 ID解析: " + String.format("0x%06X (%d)", localId, localId));

            // 更新状态显示
            if (tvStatus != null) {
                tvStatus.setText("Status: 已连接 - 手台ID: " + localId);
            }

            // 更新手台ID显示
            if (tvDeviceId != null) {
                tvDeviceId.setText("手台ID: " + localId + " (0x" + String.format("%06X", localId) + ")");
            }

        } else {
            appendLog("❌ ID响应数据包长度不足: " + data.length + "字节 (期望7字节)");
        }
    }

    // USB functionality temporarily disabled

    // === DropDownReceiver 必须实现的接口 ===
    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }
}