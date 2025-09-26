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
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * USB串口管理器
 * 负责USB设备的检测、连接、权限管理
 */
public class USBSerialManager {
    
    private static final String TAG = "USBSerialManager";
    private static final String ACTION_USB_PERMISSION = "com.saemaps.android.usbserial.USB_PERMISSION";
    
    private Context context;
    private UsbManager usbManager;
    private USBSerialListener listener;
    private UsbSerialPort currentPort;
    private UsbDeviceConnection currentConnection;
    private SerialInputOutputManager ioManager;
    private boolean isConnected = false;
    
    // 串口参数
    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = 0; // 0=无, 1=奇校验, 2=偶校验
    
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
    
    /**
     * 注册USB广播接收器
     */
    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        
        context.registerReceiver(usbReceiver, filter);
    }
    
    /**
     * USB广播接收器
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "USB设备已连接: " + device.getDeviceName());
                scanDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB设备已断开: " + device.getDeviceName());
                if (currentPort != null && device.equals(currentPort.getDriver().getDevice())) {
                    disconnect();
                }
                scanDevices();
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    Log.d(TAG, "USB权限已授予: " + device.getDeviceName());
                    connectToDevice(device);
                } else {
                    Log.w(TAG, "USB权限被拒绝: " + device.getDeviceName());
                    if (listener != null) {
                        listener.onPermissionDenied(device);
                    }
                }
            }
        }
    };
    
    /**
     * 扫描可用的USB设备
     */
    public void scanDevices() {
        List<UsbDevice> devices = new ArrayList<>();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        
        for (UsbDevice device : deviceList.values()) {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver != null) {
                devices.add(device);
                Log.d(TAG, "找到USB串口设备: " + device.getDeviceName() + 
                      " (VID:" + device.getVendorId() + ", PID:" + device.getProductId() + ")");
            }
        }
        
        if (listener != null) {
            listener.onDeviceDetected(devices);
        }
    }
    
    /**
     * 连接到指定设备
     */
    public void connectToDevice(UsbDevice device) {
        if (isConnected) {
            disconnect();
        }
        
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            Log.e(TAG, "未找到设备驱动: " + device.getDeviceName());
            if (listener != null) {
                listener.onError(new IOException("未找到设备驱动"));
            }
            return;
        }
        
        if (driver.getPorts().size() == 0) {
            Log.e(TAG, "设备没有可用端口: " + device.getDeviceName());
            if (listener != null) {
                listener.onError(new IOException("设备没有可用端口"));
            }
            return;
        }
        
        // 检查权限
        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "请求USB权限: " + device.getDeviceName());
            requestPermission(device);
            return;
        }
        
        // 打开设备连接
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "无法打开设备连接: " + device.getDeviceName());
            if (listener != null) {
                listener.onError(new IOException("无法打开设备连接"));
            }
            return;
        }
        
        // 打开串口
        currentPort = driver.getPorts().get(0); // 使用第一个端口
        currentConnection = connection;
        
        try {
            currentPort.open(connection);
            currentPort.setParameters(baudRate, dataBits, stopBits, parity);
            
            // 设置DTR和RTS
            currentPort.setDTR(true);
            currentPort.setRTS(true);
            
            // 启动数据监听
            ioManager = new SerialInputOutputManager(currentPort, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    if (listener != null) {
                        listener.onDataReceived(data);
                    }
                }
                
                @Override
                public void onRunError(Exception e) {
                    Log.e(TAG, "串口运行错误", e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            });
            ioManager.start();
            
            isConnected = true;
            Log.d(TAG, "USB设备连接成功: " + device.getDeviceName());
            if (listener != null) {
                listener.onDeviceConnected(device);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "连接设备失败", e);
            if (listener != null) {
                listener.onError(e);
            }
            cleanup();
        }
    }
    
    /**
     * 请求USB权限
     */
    private void requestPermission(UsbDevice device) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, 
            new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, permissionIntent);
    }
    
    /**
     * 发送数据
     */
    public void sendData(byte[] data) throws IOException {
        if (!isConnected || currentPort == null) {
            throw new IOException("设备未连接");
        }
        currentPort.write(data, 200); // 200ms超时
    }
    
    /**
     * 发送字符串
     */
    public void sendString(String text) throws IOException {
        sendData(text.getBytes());
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        
        if (currentPort != null) {
            try {
                currentPort.setDTR(false);
                currentPort.setRTS(false);
                currentPort.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭串口失败", e);
            }
            currentPort = null;
        }
        
        if (currentConnection != null) {
            currentConnection.close();
            currentConnection = null;
        }
        
        isConnected = false;
        Log.d(TAG, "USB设备已断开");
        if (listener != null) {
            listener.onDeviceDisconnected();
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (currentPort != null) {
            try {
                currentPort.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭串口失败", e);
            }
            currentPort = null;
        }
        if (currentConnection != null) {
            currentConnection.close();
            currentConnection = null;
        }
        isConnected = false;
    }
    
    /**
     * 设置串口参数
     */
    public void setSerialParameters(int baudRate, int dataBits, int stopBits, int parity) {
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        
        if (isConnected && currentPort != null) {
            try {
                currentPort.setParameters(baudRate, dataBits, stopBits, parity);
            } catch (Exception e) {
                Log.e(TAG, "设置串口参数失败", e);
            }
        }
    }
    
    /**
     * 设置监听器
     */
    public void setListener(USBSerialListener listener) {
        this.listener = listener;
    }
    
    /**
     * 获取连接状态
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * 获取当前设备信息
     */
    public String getCurrentDeviceInfo() {
        if (currentPort != null) {
            UsbDevice device = currentPort.getDriver().getDevice();
            return device.getDeviceName() + " (VID:" + device.getVendorId() + 
                   ", PID:" + device.getProductId() + ")";
        }
        return "未连接";
    }
    
    /**
     * 销毁管理器
     */
    public void destroy() {
        disconnect();
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "注销USB接收器失败", e);
        }
    }
}
