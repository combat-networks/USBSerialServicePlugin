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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Wrapper around usb-serial-for-android that handles discovery, permission, and IO.
 */
public class USBSerialManager {

    private static final String TAG = "USBSerialManager";
    private static final String ACTION_USB_PERMISSION = "com.saemaps.android.usbserial.USB_PERMISSION";

    private final Context context;
    private final UsbManager usbManager;

    private USBSerialListener listener;
    private UsbSerialPort currentPort;
    private UsbDeviceConnection currentConnection;
    private SerialInputOutputManager ioManager;
    private Thread legacyIoThread;
    private boolean isConnected;

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

    public USBSerialManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        context.registerReceiver(usbReceiver, filter);
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
                Log.d(TAG, "USB device attached: " + describe(device));
                scanDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB device detached: " + describe(device));
                if (currentPort != null && device.equals(currentPort.getDriver().getDevice())) {
                    disconnect();
                }
                scanDevices();
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    Log.d(TAG, "USB permission granted: " + describe(device));
                    connectToDevice(device);
                } else {
                    Log.w(TAG, "USB permission denied: " + describe(device));
                    if (listener != null) {
                        listener.onPermissionDenied(device);
                    }
                }
            }
        }
    };

    public void setListener(USBSerialListener listener) {
        this.listener = listener;
    }

    public void scanDevices() {
        List<UsbDevice> devices = new ArrayList<>();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
            if (driver != null) {
                Log.d(TAG, "Detected USB serial device: " + describe(device) + " driver=" + driver.getClass().getSimpleName());
                devices.add(device);
            } else {
                Log.v(TAG, "Skipping non-serial device: " + describe(device));
            }
        }
        if (listener != null) {
            listener.onDeviceDetected(devices);
        }
    }

    public void connectToDevice(UsbDevice device) {
        if (isConnected) {
            disconnect();
        }

        UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
        if (driver == null) {
            Log.e(TAG, "No driver match for device: " + describe(device));
            if (listener != null) {
                listener.onError(new IOException("No USB serial driver matched the device."));
            }
            return;
        }

        if (driver.getPorts().isEmpty()) {
            Log.e(TAG, "Driver has no ports: " + driver.getClass().getSimpleName());
            if (listener != null) {
                listener.onError(new IOException("Driver reported no available serial ports."));
            }
            return;
        }

        if (!usbManager.hasPermission(device)) {
            requestPermission(device);  //系统弹窗需手动允许
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open device connection: " + describe(device));
            if (listener != null) {
                listener.onError(new IOException("Failed to open USB device."));
            }
            return;
        }

        currentPort = driver.getPorts().get(0);
        currentConnection = connection;
        try {
            currentPort.open(connection);
            currentPort.setParameters(baudRate, dataBits, stopBits, parity);
            currentPort.setDTR(true);
            currentPort.setRTS(true);

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
                    Log.e(TAG, "Serial IO error", e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            });
            
            // 兼容不同版本的SerialInputOutputManager
            try {
                // 尝试使用新版本的 start() 方法
                ioManager.getClass().getMethod("start").invoke(ioManager);
                Log.d(TAG, "Using new version SerialInputOutputManager.start()");
            } catch (NoSuchMethodException e) {
                // 如果 start() 方法不存在，使用旧版本的方式启动
                Log.d(TAG, "Using legacy SerialInputOutputManager with Thread");
                legacyIoThread = new Thread(ioManager, "SerialInputOutputManager");
                legacyIoThread.start();
            } catch (InvocationTargetException e) {
                Log.e(TAG, "Failed to invoke SerialInputOutputManager.start()", e.getCause());
                throw new RuntimeException("Failed to start SerialInputOutputManager", e.getCause());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start SerialInputOutputManager", e);
                throw new RuntimeException("Failed to start SerialInputOutputManager", e);
            }

            isConnected = true;
            Log.d(TAG, "USB serial connected: " + describe(device));
            if (listener != null) {
                listener.onDeviceConnected(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open serial port", e);
            if (listener != null) {
                listener.onError(e);
            }
            cleanup();
        }
    }

    private void requestPermission(UsbDevice device) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
        Log.d(TAG, "Requesting USB permission: " + describe(device));
        usbManager.requestPermission(device, permissionIntent);
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

    public void disconnect() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        stopLegacyThread();
        if (currentPort != null) {
            try {
                currentPort.setDTR(false);
                currentPort.setRTS(false);
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
    }

    public void destroy() {
        disconnect();
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered", e);
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

    private static String describe(UsbDevice device) {
        if (device == null) {
            return "unknown";
        }
        return "VID=" + device.getVendorId() + " PID=" + device.getProductId();
    }
}





