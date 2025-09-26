package com.saemaps.android.usbserial.usbserial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.saemaps.android.usbserial.USBSerialDropDownReceiver;
import com.saemaps.android.usbserial.plugin.R;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * USB串口后台服务
 * 管理串口连接和数据缓冲
 */
public class USBSerialService extends Service implements USBSerialManager.USBSerialListener {
    
    private static final String TAG = "USBSerialService";
    private static final String CHANNEL_ID = "usb_serial_service";
    private static final int NOTIFICATION_ID = 1001;
    
    private USBSerialManager serialManager;
    private Handler mainHandler;
    private boolean isServiceRunning = false;
    
    // 数据缓冲
    private ArrayDeque<byte[]> dataQueue = new ArrayDeque<>();
    private final Object dataLock = new Object();
    
    // 服务绑定
    public class USBSerialBinder extends Binder {
        public USBSerialService getService() {
            return USBSerialService.this;
        }
    }
    
    private final IBinder binder = new USBSerialBinder();
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "USBSerialService onCreate");
        
        mainHandler = new Handler(Looper.getMainLooper());
        serialManager = new USBSerialManager(this);
        serialManager.setListener(this);
        
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "USBSerialService onStartCommand");
        
        if (!isServiceRunning) {
            startForeground(NOTIFICATION_ID, createNotification("USB串口服务已启动"));
            isServiceRunning = true;
        }
        
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "USBSerialService onDestroy");
        
        if (serialManager != null) {
            serialManager.destroy();
        }
        
        stopForeground(true);
        isServiceRunning = false;
        
        super.onDestroy();
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "USB串口服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("USB串口通信后台服务");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * 创建通知
     */
    private Notification createNotification(String contentText) {
        Intent intent = new Intent(this, USBSerialDropDownReceiver.class);
        intent.setAction(USBSerialDropDownReceiver.SHOW_PLUGIN);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB串口服务")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    /**
     * 更新通知
     */
    private void updateNotification(String contentText) {
        if (isServiceRunning) {
            Notification notification = createNotification(contentText);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }
    
    // ========== USB串口管理器回调 ==========
    
    @Override
    public void onDeviceDetected(java.util.List<android.hardware.usb.UsbDevice> devices) {
        Log.d(TAG, "检测到 " + devices.size() + " 个USB设备");
        updateNotification("检测到 " + devices.size() + " 个USB设备");
    }
    
    @Override
    public void onDeviceConnected(android.hardware.usb.UsbDevice device) {
        Log.d(TAG, "USB设备已连接: " + device.getDeviceName());
        updateNotification("已连接: " + device.getDeviceName());
    }
    
    @Override
    public void onDeviceDisconnected() {
        Log.d(TAG, "USB设备已断开");
        updateNotification("USB设备已断开");
        
        // 清空数据队列
        synchronized (dataLock) {
            dataQueue.clear();
        }
    }
    
    @Override
    public void onDataReceived(byte[] data) {
        Log.d(TAG, "接收到数据: " + data.length + " 字节");
        
        // 将数据添加到队列
        synchronized (dataLock) {
            dataQueue.add(data);
        }
        
        // 通知UI更新
        mainHandler.post(() -> {
            // 这里可以发送广播通知UI更新
            Intent intent = new Intent("com.saemaps.android.usbserial.DATA_RECEIVED");
            intent.putExtra("data", data);
            sendBroadcast(intent);
        });
    }
    
    @Override
    public void onError(Exception error) {
        Log.e(TAG, "USB串口错误", error);
        updateNotification("错误: " + error.getMessage());
    }
    
    @Override
    public void onPermissionDenied(android.hardware.usb.UsbDevice device) {
        Log.w(TAG, "USB权限被拒绝: " + device.getDeviceName());
        updateNotification("USB权限被拒绝");
    }
    
    // ========== 公共API ==========
    
    /**
     * 扫描USB设备
     */
    public void scanDevices() {
        if (serialManager != null) {
            serialManager.scanDevices();
        }
    }
    
    /**
     * 连接到设备
     */
    public void connectToDevice(android.hardware.usb.UsbDevice device) {
        if (serialManager != null) {
            serialManager.connectToDevice(device);
        }
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        if (serialManager != null) {
            serialManager.disconnect();
        }
    }
    
    /**
     * 发送数据
     */
    public void sendData(byte[] data) throws IOException {
        if (serialManager != null) {
            serialManager.sendData(data);
        } else {
            throw new IOException("串口管理器未初始化");
        }
    }
    
    /**
     * 发送字符串
     */
    public void sendString(String text) throws IOException {
        if (serialManager != null) {
            serialManager.sendString(text);
        } else {
            throw new IOException("串口管理器未初始化");
        }
    }
    
    /**
     * 设置串口参数
     */
    public void setSerialParameters(int baudRate, int dataBits, int stopBits, int parity) {
        if (serialManager != null) {
            serialManager.setSerialParameters(baudRate, dataBits, stopBits, parity);
        }
    }
    
    /**
     * 获取连接状态
     */
    public boolean isConnected() {
        return serialManager != null && serialManager.isConnected();
    }
    
    /**
     * 获取当前设备信息
     */
    public String getCurrentDeviceInfo() {
        if (serialManager != null) {
            return serialManager.getCurrentDeviceInfo();
        }
        return "服务未初始化";
    }
    
    /**
     * 获取数据队列
     */
    public ArrayDeque<byte[]> getDataQueue() {
        synchronized (dataLock) {
            ArrayDeque<byte[]> result = new ArrayDeque<>(dataQueue);
            dataQueue.clear();
            return result;
        }
    }
    
    /**
     * 获取数据队列大小
     */
    public int getDataQueueSize() {
        synchronized (dataLock) {
            return dataQueue.size();
        }
    }
}
