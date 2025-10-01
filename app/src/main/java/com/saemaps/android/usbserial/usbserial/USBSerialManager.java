package com.saemaps.android.usbserial.usbserial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import com.saemaps.android.usbserial.USBSerialPermissionReceiver;

import com.saemaps.android.maps.MapView;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Wrapper around usb-serial-for-android that handles discovery, permission, and
 * IO.
 */
public class USBSerialManager {

    private static final String TAG = "USBSerialManager";

    // 数据缓冲机制 - 参考SimpleUsbTerminal
    private final ArrayDeque<byte[]> dataBuffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private Handler mainHandler;

    // 环形缓冲区 - 用于处理完整数据包
    private RingBuffer ringBuffer;
    // 数据包相关常量
    private static final int MIN_PACKET_SIZE = 4; // 最小数据包大小（包头3字节 + 至少1字节数据）
    private static final int MAX_PACKET_SIZE = 256; // 最大数据包大小限制

    // 移除内部单例管理，改为由USBSerialLifecycle管理
    // 🔑 使用完全通用的action名称，避免包名冲突
    // 🔑 使用插件包名构建action（参考codec2插件）
    private static final String ACTION_USB_PERMISSION = "com.saemaps.android.usbserial.plugin.GRANT_USB";

    private static final String ACTION_USB_PERMISSION_GRANTED = "com.saemaps.android.USB_PERMISSION_GRANTED";
    private static final String ACTION_USB_PERMISSION_DENIED = "com.saemaps.android.USB_PERMISSION_DENIED";

    // 成员字段
    private final Context pluginContext; // 插件Context
    private final Context hostContext; // 宿主应用Context（用于创建PendingIntent）
    private final UsbManager usbManager;
    private MapView mapView; // 用于获取宿主Context
    // 移除动态注册的permission receiver，现在使用静态注册的USBSerialPermissionReceiver

    private USBSerialListener listener;
    private UsbSerialPort currentPort;
    private UsbDeviceConnection currentConnection;
    private UsbDevice currentDevice;
    private SerialInputOutputManager ioManager;
    private Thread legacyIoThread;
    private boolean isConnected;
    private volatile boolean isReconnecting;
    private volatile boolean isDisconnecting;
    private volatile boolean isDeviceDetached;

    // 重连计数器和错误分类
    private int reconnectCount = 0;
    private long lastReconnectTime = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_COOLDOWN_MS = 10000; // 10秒冷却期

    // 权限接收器
    // private USBSerialPermissionReceiver permissionReceiver;

    // 调试开关 - 用于分步调试
    private boolean debugMode = true;
    private int debugStep = 2; // 当前调试步骤

    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = UsbSerialPort.PARITY_NONE;

    // 错误分类枚举
    private enum ErrorType {
        BENIGN_CLOSE, // 良性关闭错误
        CH340_STATUS_ERROR, // CH340状态检查错误
        PERMISSION_ERROR, // 权限错误
        CONNECTION_CLOSED, // 连接正常关闭
        RECOVERABLE_ERROR, // 可恢复错误
        CRITICAL_ERROR // 严重错误
    }

    public interface USBSerialListener {
        void onDeviceDetected(List<UsbDevice> devices);

        void onDeviceConnected(UsbDevice device);

        void onDeviceDisconnected();

        void onDataReceived(byte[] data);

        void onError(Exception error);

        void onPermissionDenied(UsbDevice device);
    }

    // 构造函数 - 传入插件Context与宿主应用Context（用于PendingIntent/UsbManager）
    public USBSerialManager(Context pluginCtx, Context hostAppCtx) {
        if (pluginCtx == null) {
            throw new IllegalArgumentException("Plugin context cannot be null");
        }
        if (hostAppCtx == null) {
            throw new IllegalArgumentException("Host app context cannot be null");
        }

        this.pluginContext = pluginCtx;
        // 🔑 不再转为 ApplicationContext，保留宿主传入的 Context 以保持正确的 opPackageName/UID
        this.hostContext = hostAppCtx;

        this.usbManager = (UsbManager) this.hostContext.getSystemService(Context.USB_SERVICE);
        if (this.usbManager == null) {
            throw new IllegalStateException("USB Service not available");
        }

        // 初始化主线程Handler用于数据缓冲
        this.mainHandler = new Handler(Looper.getMainLooper());

        // 初始化环形缓冲区
        this.ringBuffer = new RingBuffer();

        Log.d(TAG, "🔑 Plugin context: " + pluginContext.getPackageName());
        Log.d(TAG, "🔑 Host context: " + hostContext.getPackageName());

        registerUsbReceiver();
    }

    /**
     * 获取单例实例 - 现在通过USBSerialLifecycle管理
     */
    public static USBSerialManager getInstance() {
        // 通过USBSerialLifecycle获取实例
        return com.saemaps.android.usbserial.USBSerialLifecycle.getUsbSerialManagerInstance();
    }

    /**
     * 设置调试模式
     * 
     * @param debugMode 是否启用调试模式
     * @param step      当前调试步骤 (1-8)
     */
    public void setDebugMode(boolean debugMode, int step) {
        this.debugMode = debugMode;
        this.debugStep = step;
        Log.d(TAG, "Debug mode: " + debugMode + ", Step: " + step);
    }

    /**
     * 设置MapView，用于获取宿主Context
     * 
     * @param mapView ATAK MapView实例
     */
    public void setMapView(MapView mapView) {
        this.mapView = mapView;
        Log.d(TAG, "MapView set: " + (mapView != null ? "not null" : "null"));
    }

    private void registerUsbReceiver() {
        // 注册USB设备连接/断开接收器（参考codec2插件使用mapViewContext）
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // 🔧 添加自定义USB设备插入广播监听
        filter.addAction("com.saemaps.android.usbserial.USB_DEVICE_ATTACHED");
        // filter.addAction(ACTION_USB_PERMISSION_GRANTED);
        // filter.addAction(ACTION_USB_PERMISSION_DENIED);

        // 🔑 使用插件Context注册BroadcastReceiver（完全参考codec2插件）
        Log.d(TAG, "🔑 Using plugin context for USB receiver registration: " + pluginContext.getPackageName());
        pluginContext.registerReceiver(usbReceiver, filter);

        // ⛔ 删掉这里对 USBSerialPermissionReceiver 的动态注册
        // 我们已经在 registerPermissionReceiverIfNeeded() 里用匿名接收器
        // 统一处理 ACTION_USB_PERMISSION 了，避免双重注册/重复回调

        Log.d(TAG, "🔐 Registered USB permission receiver for action: " + ACTION_USB_PERMISSION);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
                    "com.saemaps.android.usbserial.USB_DEVICE_ATTACHED".equals(action)) {
                Log.d(TAG, "🔍 STEP1: USB device attached: " + describe(device));
                scanDevices();

                // 🔧 自动连接检测到的USB串口设备
                UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
                if (driver != null) {
                    Log.d(TAG, "🔌 STEP1: Auto-connecting to detected serial device: " + describe(device));
                    connectToDevice(device);
                } else {
                    Log.d(TAG, "🔍 STEP1: Device is not a serial device, skipping auto-connect");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "🔍 STEP1: USB device detached: " + describe(device));
                isDeviceDetached = true;
                if (currentPort != null && device.equals(currentPort.getDriver().getDevice())) {
                    if (!debugMode || debugStep > 1) { // 调试模式下步骤1不执行断开连接
                        disconnect();
                    }
                }
                scanDevices();
            } else if (ACTION_USB_PERMISSION_GRANTED.equals(action)) {
                if (debugMode && debugStep == 1) {
                    Log.d(TAG, "🔍 STEP1: Permission granted broadcast received but ignored in debug mode");
                    return;
                }
                Log.d(TAG, "🔐 STEP2: USB permission granted via receiver: " + describe(device));
                connectToDevice(device);
            } else if (ACTION_USB_PERMISSION_DENIED.equals(action)) {
                if (debugMode && debugStep == 1) {
                    Log.d(TAG, "🔍 STEP1: Permission denied broadcast received but ignored in debug mode");
                    return;
                }
                Log.w(TAG, "🔐 STEP2: USB permission denied via receiver: " + describe(device));
                if (listener != null) {
                    listener.onPermissionDenied(device);
                }
            }
        }
    };

    public void setListener(USBSerialListener listener) {
        Log.d(TAG, "🔧 setListener called with " + (listener != null ? "NOT NULL" : "NULL") + " listener");
        this.listener = listener;
        Log.d(TAG, "🔧 listener set successfully");
    }

    public USBSerialListener getListener() {
        return this.listener;
    }

    public void scanDevices() {
        Log.d(TAG, "🔍 STEP1: Starting device scan...");
        List<UsbDevice> devices = new ArrayList<>();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        Log.d(TAG, "🔍 STEP1: Found " + deviceList.size() + " total USB devices");

        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "🔍 STEP1: Checking device: " + describe(device));

            UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
            if (driver != null) {
                Log.d(TAG, "✅ STEP1: Detected USB serial device: " + describe(device) +
                        " driver=" + driver.getClass().getSimpleName() +
                        " ports=" + driver.getPorts().size());
                devices.add(device);
            } else {
                Log.v(TAG, "❌ STEP1: Skipping non-serial device: " + describe(device));
            }
        }

        Log.d(TAG, "🔍 STEP1: Scan complete. Found " + devices.size() + " serial devices");
        if (listener != null) {
            listener.onDeviceDetected(devices);
        }
    }

    // 连接入口里（STEP2）：（只要没权限就 requestPermission；有权限直连）
    public void connectToDevice(UsbDevice device) {
        Log.d(TAG, "🔌 connectToDevice called for device: " + describe(device));
        Log.d(TAG, "🔌 Debug mode: " + debugMode + ", debug step: " + debugStep);

        // 🔧 防止重复连接：如果正在连接同一个设备，直接返回
        if (isConnected && currentPort != null) {
            UsbDevice connectedDevice = currentPort.getDriver().getDevice();
            if (connectedDevice.getVendorId() == device.getVendorId() &&
                    connectedDevice.getProductId() == device.getProductId()) {
                Log.d(TAG, "🔌 Device already connected (VID=" + device.getVendorId() +
                        " PID=" + device.getProductId() + "), skipping duplicate connection");
                return;
            }
        }

        // reset transient flags for a fresh connection attempt
        isDeviceDetached = false;
        isDisconnecting = false;

        if (debugMode && debugStep == 1) {
            Log.d(TAG, "🔍 STEP1: connectToDevice called but ignored in debug mode");
            return;
        }

        if (debugMode && debugStep >= 2) {
            Log.d(TAG, "🔐 STEP2: Starting connection process for device: " + describe(device));
        }

        if (isConnected) {
            Log.d(TAG, "🔌 Already connected, disconnecting first");
            disconnect();
        }

        UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
        if (driver == null) {
            Log.e(TAG, "No driver match for device: " + describe(device));
            if (listener != null) {
                listener.onError(new IOException("No USB serial driver matched the device."));
            }
            notifyError("No driver", new IOException("No driver"));
            return;
        }

        if (debugMode && debugStep >= 2) {
            Log.d(TAG, "🔐 STEP2: Driver found: " + driver.getClass().getSimpleName());
        }

        if (driver.getPorts().isEmpty()) {
            Log.e(TAG, "Driver has no ports: " + driver.getClass().getSimpleName());
            if (listener != null) {
                listener.onError(new IOException("Driver reported no available serial ports."));
            }
            return;
        }

        if (debugMode && debugStep >= 2) {
            Log.d(TAG, "🔐 STEP2: Driver has " + driver.getPorts().size() + " ports available");
        }

        boolean hasPermission = usbManager.hasPermission(device);
        Log.d(TAG, "🔐 Device permission status: " + hasPermission);
        if (!hasPermission) {
            if (debugMode && debugStep >= 2) {
                Log.d(TAG, "🔐 STEP2: Device requires permission, requesting...");
            }
            requestPermission(device); // ✅ 用宿主 appContext + 不设 package
            return;
        }
        Log.d(TAG, "🔐 Device already has permission, opening port directly...");
        openPortAfterPermission(device);
    }

    // ✅ 正确的权限请求（无 setPackage；用 appContext；不重复 new UsbManager）
    private void requestPermission(UsbDevice device) {
        try {
            if (debugMode && debugStep >= 2) {
                Log.d(TAG, "🔐 STEP2: Requesting USB permission for device: " + describe(device));
            }
            // 不再需要动态注册permission receiver，使用静态注册的USBSerialPermissionReceiver

            // 🔑 关键修复：使用宿主Context创建PendingIntent（解决UID不匹配问题）
            Log.d(TAG, "🔑 Using host context for PendingIntent: " + hostContext.getPackageName());

            // ✅ 创建权限请求Intent（不声明包名/组件，避免“send as package”安全异常）
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);

            // 🔑 选择用于创建 PendingIntent 的真正宿主 Context
            Context piOwnerContext = hostContext;
            try {
                if (mapView != null && mapView.getContext() != null
                        && mapView.getContext().getApplicationContext() != null) {
                    piOwnerContext = mapView.getContext().getApplicationContext();
                }
            } catch (Exception ignored) {
            }

            // 🔑 使用宿主Context创建PendingIntent（创建者为宿主UID）
            PendingIntent pi = PendingIntent.getBroadcast(
                    piOwnerContext,
                    0, // 🔑 使用简单requestCode（参考codec2插件）
                    permissionIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT // Android 12+ 必须
            );

            Log.d(TAG, "🔐 PendingIntent created by package: " + piOwnerContext.getPackageName());

            Log.d(TAG, "🔐 STEP2: USB permission request sent for device: "
                    + String.format("VID=%04X PID=%04X", device.getVendorId(), device.getProductId()));
            Log.d(TAG, "🔐 Permission intent action: " + ACTION_USB_PERMISSION);
            Log.d(TAG, "🔐 Using host context package: " + hostContext.getPackageName());

            usbManager.requestPermission(device, pi);

            Log.d(TAG, "🔐 STEP2: Sent USB permission request for device VID=" + device.getVendorId() + " PID="
                    + device.getProductId());

        } catch (Exception e) {
            Log.e(TAG, "❌ Exception in requestPermission", e);
            if (listener != null) {
                listener.onPermissionDenied(device);
            }
        }
    }

    // 真正打开端口（已有权限时走这里；或权限授予回调后走这里）
    public void openPortAfterPermission(UsbDevice device) {
        try {
            if (debugMode && debugStep >= 2) {
                Log.d(TAG, "🔐 STEP2: Device permission already granted, opening connection...");
            }

            UsbDeviceConnection conn = usbManager.openDevice(device);
            if (conn == null) {
                Log.e(TAG, "Failed to open device connection: " + describe(device));
                if (debugMode && debugStep >= 2) {
                    Log.e(TAG, "🔐 STEP2: Failed to open USB device connection");
                }
                if (listener != null) {
                    listener.onError(new IOException("Failed to open USB device."));
                }
                return;
            }

            if (debugMode && debugStep >= 2) {
                Log.d(TAG, "🔐 STEP2: USB device connection opened successfully");
            }

            UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
            if (driver == null) {
                Log.e(TAG, "Driver disappeared for device: " + describe(device));
                if (listener != null) {
                    listener.onError(new IOException("Driver disappeared"));
                }
                return;
            }

            currentPort = driver.getPorts().get(0);
            currentDevice = device;
            currentConnection = conn;

            try {
                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: Opening serial port...");
                }
                currentPort.open(conn);

                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: Setting serial port parameters: baud=" + baudRate +
                            ", data=" + dataBits + ", stop=" + stopBits + ", parity=" + parity);
                }
                currentPort.setParameters(baudRate, dataBits, stopBits, parity);

                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: Setting DTR and RTS signals...");
                }
                currentPort.setDTR(true);
                currentPort.setRTS(true);

                // Flush any stale data in device buffers where supported
                try {
                    currentPort.purgeHwBuffers(true, true);
                } catch (Throwable ignored) {
                }

                // 🔧 SimpleUsbTerminal风格：使用Thread启动IO管理器（3.8.0版本）
                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: Starting SerialInputOutputManager (SimpleUsbTerminal style)...");
                }

                // 使用统一的创建方法
                createSerialInputOutputManager();

                // 延迟检查线程状态
                mainHandler.postDelayed(() -> {
                    Log.d(TAG, "🔧 Thread state after 1s: " + legacyIoThread.getState());
                    Log.d(TAG, "🔧 Connection status: " + (isConnected ? "CONNECTED" : "DISCONNECTED"));
                    Log.d(TAG, "🔧 Port status: " + (currentPort != null ? "OPEN" : "CLOSED"));
                }, 1000);

                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: SerialInputOutputManager started successfully");
                }

                isConnected = true;
                // 连接成功，重置重连计数器
                reconnectCount = 0;
                Log.d(TAG, "USB serial connected: " + describe(device));

                // 连接成功，不需要发送测试数据
                Log.d(TAG, "🔧 Connection established successfully");
                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: USB serial connection established successfully!");
                }
                if (listener != null) {
                    listener.onDeviceConnected(device);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to open serial port", e);
                if (debugMode && debugStep >= 2) {
                    Log.e(TAG, "🔐 STEP2: Failed to establish USB serial connection: " + e.getMessage());
                }
                if (listener != null) {
                    listener.onError(e);
                }
                cleanup();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open device", e);
            notifyError("Connection failed", e);
            closeSilently();
        }
    }

    public void sendData(byte[] data) throws IOException {
        if (!isConnected || currentPort == null) {
            throw new IOException("Serial port not connected");
        }
        currentPort.write(data, 200); // 200 ms timeout
        Log.d(TAG, "TX bytes=" + data.length);
    }

    public void sendString(String text) throws IOException {
        sendData(text.getBytes());
    }

    /**
     * 权限授予回调方法
     * 当USBSerialPermissionReceiver接收到权限授予广播时调用
     */
    public void onPermissionGranted(UsbDevice device) {
        Log.d(TAG, "✅ Permission granted for device: " +
                String.format("VID=%04X PID=%04X", device.getVendorId(), device.getProductId()));

        // 继续连接设备
        connectToDevice(device);
    }

    public void disconnect() {
        try {
            isDisconnecting = true;

            // 清理环形缓冲区
            clearRingBuffer();

            if (ioManager != null) {
                try {
                    ioManager.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping ioManager", e);
                }
                ioManager = null;
            }
            stopLegacyThread();
            if (currentPort != null) {
                try {
                    try {
                        currentPort.setDTR(false);
                    } catch (Exception ignored) {
                    }
                    try {
                        currentPort.setRTS(false);
                    } catch (Exception ignored) {
                    }
                    currentPort.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing serial port", e);
                }
                currentPort = null;
            }
            if (currentConnection != null) {
                try {
                    currentConnection.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing connection", e);
                }
                currentConnection = null;
            }
            if (isConnected && listener != null) {
                try {
                    listener.onDeviceDisconnected();
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener.onDeviceDisconnected", e);
                }
            }
            isConnected = false;
            Log.d(TAG, "Serial connection closed");
        } catch (Exception e) {
            Log.e(TAG, "Error in disconnect", e);
        } finally {
            isDisconnecting = false;
        }
    }

    public void destroy() {
        closeSilently();
        // 不再需要注销动态注册的permission receiver，使用静态注册的USBSerialPermissionReceiver
        // disconnect();
        try {
            pluginContext.unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "USB receiver already unregistered", e);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getCurrentDeviceInfo() {
        if (currentPort != null) {
            UsbDevice device = currentPort.getDriver().getDevice();
            return describe(device);
        }
        return "not connected";
    }

    public void setSerialParameters(int baudRate, int dataBits, int stopBits, int parity) {
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        if (isConnected && currentPort != null) {
            try {
                currentPort.setParameters(baudRate, dataBits, stopBits, parity);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update serial parameters", e);
            }
        }
    }

    private void cleanup() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        stopLegacyThread();
        if (currentPort != null) {
            try {
                currentPort.close();
            } catch (Exception ignored) {
            }
            currentPort = null;
        }
        if (currentConnection != null) {
            currentConnection.close();
            currentConnection = null;
        }
        currentDevice = null;
        isConnected = false;
    }

    private void stopLegacyThread() {
        try {
            if (legacyIoThread != null && legacyIoThread.isAlive()) {
                Log.d(TAG, "Stopping legacy SerialInputOutputManager thread");
                legacyIoThread.interrupt();
                try {
                    legacyIoThread.join(1000); // wait up to 1s for the legacy IO thread to finish
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting for legacy thread to stop", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping legacy thread", e);
                }
                legacyIoThread = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in stopLegacyThread", e);
            legacyIoThread = null;
        }
    }

    /**
     * 智能错误分类
     */
    private ErrorType classifyError(String message) {
        if (isDisconnecting || isDeviceDetached || message.contains("Connection closed")) {
            return ErrorType.BENIGN_CLOSE;
        }

        if (message.contains("USB get_status request failed")) {
            return ErrorType.CH340_STATUS_ERROR;
        }

        if (message.contains("Permission denied") || message.contains("Access denied")) {
            return ErrorType.PERMISSION_ERROR;
        }

        if (message.contains("Connection closed") || message.contains("Device disconnected")) {
            return ErrorType.CONNECTION_CLOSED;
        }

        if (message.contains("USB") || message.contains("Serial") || message.contains("IO")) {
            return ErrorType.RECOVERABLE_ERROR;
        }

        return ErrorType.CRITICAL_ERROR;
    }

    // 🔧 SimpleUsbTerminal风格：移除自动重连机制
    // 当连接出错时，直接断开连接，让用户重新连接

    // 🔧 SimpleUsbTerminal风格：移除所有自动重连机制
    // 连接出错时直接断开，让用户手动重新连接

    private static String describe(UsbDevice device) {
        if (device == null) {
            return "unknown";
        }
        return "VID=" + device.getVendorId() + " PID=" + device.getProductId();
    }

    // 移除动态注册的permission receiver方法，现在使用静态注册的USBSerialPermissionReceiver

    /**
     * 安全关闭串口
     */
    private void closeSilently() {
        try {
            if (currentPort != null) {
                currentPort.close();
                Log.d(TAG, "Serial connection closed");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Exception while closing serial port", e);
        }
    }

    /**
     * 权限被拒绝通知
     */
    private void notifyPermissionDenied(UsbDevice device) {
        Log.w(TAG, "⚠️ Permission denied for device VID="
                + device.getVendorId() + " PID=" + device.getProductId());
        // 如果你有 listener，可以在这里回调： listener.onPermissionDenied(device);
    }

    /**
     * 连接成功通知
     */
    private void notifyConnected(UsbDevice device) {
        Log.d(TAG, "✅ Serial device connected successfully: VID="
                + device.getVendorId() + " PID=" + device.getProductId());
        // 如果你有 listener，可以在这里回调： listener.onConnected(device);
    }

    /**
     * 通用错误通知
     */
    private void notifyError(String message, Exception e) {
        Log.e(TAG, "❌ " + message, e);
        // 如果你有 listener，可以在这里回调： listener.onError(message, e);
    }

    /**
     * 简单错误通知（无异常）
     */
    private void notifyError(String message) {
        Log.e(TAG, "❌ " + message);
        // 如果你有 listener，可以在这里回调： listener.onError(message, null);
    }

    /**
     * 检查是否是CH340相关错误
     */
    private boolean isCH340Error(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }

        String message = e.getMessage().toLowerCase();
        return message.contains("ch340") ||
                message.contains("usb status check failed") ||
                message.contains("usb status error") ||
                message.contains("usb get_status request failed");
    }

    /**
     * 创建SerialInputOutputManager
     */
    private void createSerialInputOutputManager() {
        if (currentPort == null) {
            Log.e(TAG, "❌ Cannot create SerialInputOutputManager: port is null");
            return;
        }

        Log.d(TAG, "🔧 Creating SerialInputOutputManager with port: NOT NULL");

        ioManager = new SerialInputOutputManager(currentPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                // 🔧 使用环形缓冲区处理数据包完整性
                Log.d(TAG, "📥 Received data: " + data.length + " bytes");

                // 使用主线程Handler处理数据，避免阻塞IO线程
                mainHandler.post(() -> {
                    try {
                        // 将数据写入环形缓冲区
                        int written = ringBuffer.write(data);
                        Log.d(TAG, "📝 Written " + written + " bytes to ring buffer");

                        // 检查并提取完整数据包
                        processCompletePackets();

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error processing data in ring buffer", e);
                    }
                });
            }

            @Override
            public void onRunError(Exception e) {
                String message = e != null && e.getMessage() != null ? e.getMessage() : "";

                Log.w(TAG, "Serial IO error: " + message);

                // 检查是否是CH340相关错误
                if (isCH340Error(e)) {
                    Log.w(TAG, "⚠️ CH340 USB status error detected - checking thread status");

                    // 检查线程状态
                    if (legacyIoThread != null && legacyIoThread.isAlive()) {
                        Log.d(TAG, "🔍 Thread still alive, but USB status failed - attempting restart");
                        restartSerialInputOutputManager();
                    } else {
                        Log.w(TAG, "🔍 Thread terminated due to USB status error - connection lost");
                        handleConnectionLost(message);
                    }
                    return;
                }

                // 其他错误：直接断开连接
                Log.i(TAG, "Connection lost: " + message);
                handleConnectionLost(message);
            }
        });

        // 3.8.0版本需要使用Thread方式启动
        legacyIoThread = new Thread(ioManager, "SerialInputOutputManager");
        legacyIoThread.start();
        Log.d(TAG, "SerialInputOutputManager started successfully with Thread");

        // 检查线程状态
        Log.d(TAG, "🔧 Thread state after start: " + legacyIoThread.getState());
    }

    /**
     * 重启SerialInputOutputManager
     */
    private void restartSerialInputOutputManager() {
        try {
            Log.i(TAG, "🔄 Restarting SerialInputOutputManager due to CH340 error...");

            // 停止当前的IO管理器
            if (legacyIoThread != null && legacyIoThread.isAlive()) {
                Log.d(TAG, "🛑 Stopping current SerialInputOutputManager");
                legacyIoThread.interrupt();
                try {
                    legacyIoThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 重新创建SerialInputOutputManager
            if (currentPort != null) {
                Log.d(TAG, "🔄 Creating new SerialInputOutputManager");
                createSerialInputOutputManager();

                if (legacyIoThread != null && legacyIoThread.isAlive()) {
                    Log.i(TAG, "✅ SerialInputOutputManager restarted successfully");
                } else {
                    Log.w(TAG, "❌ Failed to restart SerialInputOutputManager");
                    handleConnectionLost("Failed to restart SerialInputOutputManager");
                }
            } else {
                Log.w(TAG, "❌ Port not available for restart");
                handleConnectionLost("Port not available for restart");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error restarting SerialInputOutputManager", e);
            handleConnectionLost("Error restarting SerialInputOutputManager: " + e.getMessage());
        }
    }

    /**
     * 处理连接丢失
     */
    private void handleConnectionLost(String reason) {
        Log.i(TAG, "🔌 Connection lost: " + reason);

        if (listener != null) {
            listener.onDeviceDisconnected();
        }

        // 清理连接状态
        cleanupConnection();
    }

    /**
     * 清理连接状态 - SimpleUsbTerminal风格
     */
    private void cleanupConnection() {
        Log.d(TAG, "🧹 Cleaning up connection state");

        isConnected = false;

        // 清理环形缓冲区
        clearRingBuffer();

        // 停止IO管理器
        if (ioManager != null) {
            try {
                ioManager.setListener(null);
                ioManager.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping IO manager: " + e.getMessage());
            }
            ioManager = null;
        }

        // 停止线程
        if (legacyIoThread != null) {
            try {
                legacyIoThread.interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Error interrupting IO thread: " + e.getMessage());
            }
            legacyIoThread = null;
        }

        // 关闭串口
        if (currentPort != null) {
            try {
                currentPort.setDTR(false);
                currentPort.setRTS(false);
            } catch (Exception ignored) {
            }
            try {
                currentPort.close();
            } catch (Exception ignored) {
            }
            currentPort = null;
        }

        // 关闭USB连接
        if (currentConnection != null) {
            try {
                currentConnection.close();
            } catch (Exception ignored) {
            }
            currentConnection = null;
        }

        currentDevice = null;
        Log.d(TAG, "✅ Connection cleanup completed");
    }

    /**
     * 处理完整数据包
     * 从环形缓冲区中提取完整的可变长度数据包并发送给监听器
     * 数据包格式：前2字节包头(0x0068) + 1字节包长度 + 1字节命令类型 + 数据内容
     */
    private void processCompletePackets() {
        if (ringBuffer == null || listener == null) {
            return;
        }

        // 循环提取完整数据包
        while (ringBuffer.hasCompleteVariablePacket() > 0) {
            byte[] packet = ringBuffer.readVariablePacket();
            if (packet != null && packet.length >= MIN_PACKET_SIZE && packet.length <= MAX_PACKET_SIZE) {
                Log.d(TAG, "📦 Extracted complete packet: " + packet.length + " bytes");

                // 解析数据包信息
                parsePacketInfo(packet);

                try {
                    listener.onDataReceived(packet);
                    Log.d(TAG, "📤 Packet sent to listener successfully");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error sending packet to listener", e);
                }
            } else if (packet != null) {
                Log.w(TAG, "⚠️ Invalid packet size: " + packet.length + " bytes");
            }
        }

        // 记录缓冲区状态
        Log.v(TAG, "🔍 Ring buffer status: " + ringBuffer.getStatus());
    }

    /**
     * 解析数据包信息（用于调试和日志）
     * 
     * @param packet 数据包
     */
    private void parsePacketInfo(byte[] packet) {
        if (packet.length < 4) {
            Log.w(TAG, "⚠️ Packet too short: " + packet.length + " bytes");
            return;
        }

        // 检查包头 - 修复字节序问题
        // 存储方式: 0x68 0x00 (大端序)，接收时应该强制转换为 0x0068
        int header = ((packet[1] & 0xFF) << 8) | (packet[0] & 0xFF);
        if (header != 0x0068) {
            Log.w(TAG, "⚠️ Invalid packet header: 0x" + Integer.toHexString(header).toUpperCase());
            return;
        }

        // 解析包长度和命令类型
        int packetDataLength = packet[2] & 0xFF;
        int commandType = packet[3] & 0xFF;
        int totalLength = packetDataLength + 3;

        Log.d(TAG, String.format("📋 Packet info: Header=0x0068, DataLen=%d, Cmd=0x%02X, Total=%d",
                packetDataLength, commandType, totalLength));

        // 根据命令类型记录详细信息
        switch (commandType) {
            case 0x55:
                Log.d(TAG, "🔋 Power-on response packet");
                break;
            case 0x02:
                Log.d(TAG, "🆔 Device ID query response packet");
                break;
            case 0xCC:
                Log.d(TAG, "📍 Location data packet");
                break;
            default:
                Log.d(TAG, "❓ Unknown command type: 0x" + Integer.toHexString(commandType).toUpperCase());
                break;
        }
    }

    /**
     * 获取环形缓冲区状态（用于调试）
     * 
     * @return 缓冲区状态信息
     */
    public String getRingBufferStatus() {
        if (ringBuffer != null) {
            return ringBuffer.getStatus();
        }
        return "Ring buffer not initialized";
    }

    /**
     * 清空环形缓冲区
     */
    public void clearRingBuffer() {
        if (ringBuffer != null) {
            ringBuffer.clear();
            Log.d(TAG, "🧹 Ring buffer cleared");
        }
    }

}