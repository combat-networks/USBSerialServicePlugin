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
    private TextView tvLog;
    private ScrollView logScroll;

    // 使用现有的USB串口管理器
    private USBSerialManager usbSerialManager;
    private List<UsbDevice> detectedDevices = new ArrayList<>();

    public USBSerialDropDownReceiver(MapView mapView, Context context) {
        super(mapView);
        Log.d(TAG, "USBSerialDropDownReceiver constructor called");
        this.pluginContext = context;

        try {
            Log.d(TAG, "About to inflate layout");
            rootView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
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
                                tvStatus.setText("Status: 已连接到 " + device.getVendorId() + ":" + device.getProductId());
                            });
                        }

                        @Override
                        public void onDeviceDisconnected() {
                            mainHandler.post(() -> {
                                appendLog("❌ 设备已断开连接");
                                tvStatus.setText("Status: 设备已断开");
                            });
                        }

                        @Override
                        public void onDataReceived(byte[] data) {
                            Log.d(TAG, "🎯 onDataReceived called with " + data.length + " bytes");
                            mainHandler.post(() -> {
                                Log.d(TAG, "🎯 onDataReceived UI update started");
                                Log.d(TAG, "🎯 tvLog is null: " + (tvLog == null));
                                Log.d(TAG, "🎯 logScroll is null: " + (logScroll == null));

                                // 🔧 修复：将二进制数据转换为十六进制显示
                                String hexData = bytesToHex(data);
                                String dataInfo = String.format("📥 收到数据 (%d字节): %s", data.length, hexData);
                                Log.d(TAG, "🎯 About to append log: " + dataInfo);

                                if (tvLog != null) {
                                    appendLog(dataInfo);
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
                                tvStatus.setText("Status: 错误 - " + error.getMessage());
                            });
                        }

                        @Override
                        public void onPermissionDenied(UsbDevice device) {
                            mainHandler.post(() -> {
                                appendLog("❌ 权限被拒绝: " + device.getVendorId() + ":" + device.getProductId());
                                tvStatus.setText("Status: 权限被拒绝");
                            });
                        }
                    });
                    Log.d(TAG, "USBSerialManager initialized successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing USBSerialManager", e);
                mainHandler.post(() -> {
                    appendLog("❌ USB管理器初始化失败: " + e.getMessage());
                    tvStatus.setText("Status: 初始化失败");
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
            tvLog = rootView.findViewById(R.id.tv_log);
            logScroll = rootView.findViewById(R.id.log_scroll);

            Log.d(TAG, "TextViews found - Status: " + (tvStatus != null ? "yes" : "no") +
                    ", Devices: " + (tvDevices != null ? "yes" : "no") +
                    ", Log: " + (tvLog != null ? "yes" : "no") +
                    ", Scroll: " + (logScroll != null ? "yes" : "no"));

            Button btnScan = rootView.findViewById(R.id.btn_scan);
            Button btnConnect = rootView.findViewById(R.id.btn_connect_first);
            Button btnDisconnect = rootView.findViewById(R.id.btn_disconnect);
            Button btnSend = rootView.findViewById(R.id.btn_send_test);

            Log.d(TAG, "Buttons found - Scan: " + (btnScan != null ? "yes" : "no") +
                    ", Connect: " + (btnConnect != null ? "yes" : "no") +
                    ", Disconnect: " + (btnDisconnect != null ? "yes" : "no") +
                    ", Send: " + (btnSend != null ? "yes" : "no"));

            btnScan.setOnClickListener(v -> {
                appendLog("🔍 步骤1: 开始USB设备检测...");
                if (usbSerialManager != null) {
                    // 启用调试模式，步骤1
                    usbSerialManager.setDebugMode(true, 1);
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
                appendLog("📤 步骤6: 发送测试数据...");
                // 启用调试模式，步骤6
                usbSerialManager.setDebugMode(true, 6);
                String testData = "Hello USB Serial!";
                try {
                    usbSerialManager.sendData(testData.getBytes());
                    appendLog("✅ 测试数据发送成功");
                } catch (Exception e) {
                    appendLog("❌ 发送测试数据失败: " + e.getMessage());
                }
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
            tvDevices.setText("未发现串口设备\n请检查:\n1. USB设备是否已连接\n2. 设备是否支持串口通信\n3. 驱动是否匹配");
            appendLog("⚠️ 未发现任何串口设备");
        } else {
            StringBuilder deviceListText = new StringBuilder("发现串口设备:\n");
            for (int i = 0; i < devices.size(); i++) {
                UsbDevice device = devices.get(i);
                deviceListText.append(String.format("%d. VID:%04X PID:%04X\n",
                        i + 1, device.getVendorId(), device.getProductId()));
            }
            tvDevices.setText(deviceListText.toString());
            appendLog("✅ 步骤1验证成功: 发现 " + devices.size() + " 个串口设备");
        }
    }

    private void appendLog(String message) {
        String current = tvLog.getText().toString();
        if ("Waiting...".contentEquals(current) || current.isEmpty()) {
            tvLog.setText(message);
        } else {
            tvLog.append("\n" + message);
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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