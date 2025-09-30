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
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import com.saemaps.android.usbserial.USBSerialPermissionReceiver;

import com.saemaps.android.maps.MapView;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Wrapper around usb-serial-for-android that handles discovery, permission, and
 * IO.
 */
public class USBSerialManager {

    private static final String TAG = "USBSerialManager";

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

    // 权限接收器
    // private USBSerialPermissionReceiver permissionReceiver;

    // 调试开关 - 用于分步调试
    private boolean debugMode = true;
    private int debugStep = 2; // 当前调试步骤

    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = UsbSerialPort.PARITY_NONE;

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
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "🔍 STEP1: USB device attached: " + describe(device));
                scanDevices();
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
        this.listener = listener;
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

                ioManager = new SerialInputOutputManager(currentPort, new SerialInputOutputManager.Listener() {
                    @Override
                    public void onNewData(byte[] data) {
                        Log.d(TAG, "RX bytes=" + data.length);
                        if (listener != null) {
                            listener.onDataReceived(data);
                        }
                    }

                    @Override
                    public void onRunError(Exception e) {
                        // Suppress benign errors during intentional disconnect/detach
                        String message = e != null && e.getMessage() != null ? e.getMessage() : "";
                        boolean isBenignClose = isDisconnecting || isDeviceDetached
                                || message.contains("Connection closed");
                        if (isBenignClose) {
                            Log.w(TAG, "Serial IO ended due to close/detach: " + message);
                            return;
                        }
                        Log.e(TAG, "Serial IO error", e);
                        if (listener != null) {
                            listener.onError(e);
                        }
                        // Attempt graceful auto-reconnect with short backoff
                        triggerAutoReconnect("IO error: " + message);
                    }
                });

                // 兼容不同版本的SerialInputOutputManager
                if (debugMode && debugStep >= 2) {
                    Log.d(TAG, "🔐 STEP2: Starting SerialInputOutputManager...");
                }
                try {
                    // 尝试使用新版本的 start() 方法
                    ioManager.getClass().getMethod("start").invoke(ioManager);
                    Log.d(TAG, "Using new version SerialInputOutputManager.start()");
                    if (debugMode && debugStep >= 2) {
                        Log.d(TAG, "🔐 STEP2: SerialInputOutputManager started with new version method");
                    }
                } catch (NoSuchMethodException e) {
                    // 如果 start() 方法不存在，使用旧版本的方式启动
                    Log.d(TAG, "Using legacy SerialInputOutputManager with Thread");
                    if (debugMode && debugStep >= 2) {
                        Log.d(TAG, "🔐 STEP2: SerialInputOutputManager started with legacy Thread method");
                    }
                    legacyIoThread = new Thread(ioManager, "SerialInputOutputManager");
                    legacyIoThread.start();
                } catch (InvocationTargetException e) {
                    Log.e(TAG, "Failed to invoke SerialInputOutputManager.start()", e.getCause());
                    if (debugMode && debugStep >= 2) {
                        Log.e(TAG, "🔐 STEP2: Failed to start SerialInputOutputManager - invocation error");
                    }
                    throw new RuntimeException("Failed to start SerialInputOutputManager", e.getCause());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start SerialInputOutputManager", e);
                    if (debugMode && debugStep >= 2) {
                        Log.e(TAG, "🔐 STEP2: Failed to start SerialInputOutputManager - general error");
                    }
                    throw new RuntimeException("Failed to start SerialInputOutputManager", e);
                }

                isConnected = true;
                Log.d(TAG, "USB serial connected: " + describe(device));
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
        isDisconnecting = true;
        if (ioManager != null) {
            ioManager.stop();
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
            currentConnection.close();
            currentConnection = null;
        }
        if (isConnected && listener != null) {
            listener.onDeviceDisconnected();
        }
        isConnected = false;
        Log.d(TAG, "Serial connection closed");
        isDisconnecting = false;
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
        if (legacyIoThread != null && legacyIoThread.isAlive()) {
            Log.d(TAG, "Stopping legacy SerialInputOutputManager thread");
            legacyIoThread.interrupt();
            try {
                legacyIoThread.join(1000); // wait up to 1s for the legacy IO thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for legacy thread to stop", e);
                Thread.currentThread().interrupt();
            }
            legacyIoThread = null;
        }
    }

    private void triggerAutoReconnect(String reason) {
        if (isReconnecting) {
            Log.w(TAG, "Reconnect already in progress, ignore. reason=" + reason);
            return;
        }
        if (isDisconnecting || isDeviceDetached) {
            Log.w(TAG, "Skip auto-reconnect due to active disconnect/detach. reason=" + reason);
            return;
        }
        if (currentDevice == null) {
            Log.w(TAG, "No device to reconnect to. reason=" + reason);
            return;
        }
        isReconnecting = true;
        new Thread(() -> {
            try {
                Log.w(TAG, "Attempting auto-reconnect in 500ms. reason=" + reason);
                Thread.sleep(500);
                try {
                    disconnect();
                } catch (Exception ignored) {
                }
                connectToDevice(currentDevice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                isReconnecting = false;
            }
        }, "USBSerial-AutoReconnect").start();
    }

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

}