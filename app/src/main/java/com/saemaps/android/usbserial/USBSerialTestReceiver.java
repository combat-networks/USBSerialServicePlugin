package com.saemaps.android.usbserial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.saemaps.android.usbserial.usbserial.USBSerialManager;

import java.util.List;

public class USBSerialTestReceiver extends BroadcastReceiver {

    private static final String TAG = "USBSerialTestReceiver";
    public static final String SHOW_PLUGIN = "com.saemaps.android.usbserial.SHOW_PLUGIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "USBSerialTestReceiver onReceive called with intent: "
                + (intent != null ? intent.getAction() : "null"));

        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Received null intent or action");
            return;
        }

        if (SHOW_PLUGIN.equals(intent.getAction())) {
            Log.d(TAG, "SHOW_PLUGIN broadcast received");

            // 测试USB管理器初始化
            try {
                USBSerialManager manager = USBSerialLifecycle.getUsbSerialManagerInstance();
                if (manager != null) {
                    Log.d(TAG, "USBSerialManager instance found, setting listener and starting device scan...");

                    // 🔑 设置listener以接收回调
                    manager.setListener(new USBSerialManager.USBSerialListener() {
                        @Override
                        public void onDeviceDetected(List<UsbDevice> devices) {
                            Log.d(TAG, "🔍 检测到 " + devices.size() + " 个USB设备");
                            for (UsbDevice device : devices) {
                                Log.d(TAG, "📱 设备: VID=" + device.getVendorId() + " PID=" + device.getProductId());
                            }
                        }

                        @Override
                        public void onDeviceConnected(UsbDevice device) {
                            Log.d(TAG, "✅ 设备已连接: VID=" + device.getVendorId() + " PID=" + device.getProductId());
                        }

                        @Override
                        public void onDeviceDisconnected() {
                            Log.d(TAG, "❌ 设备已断开");
                        }

                        @Override
                        public void onDataReceived(byte[] data) {
                            Log.d(TAG, "📨 接收到数据: " + new String(data));
                        }

                        @Override
                        public void onError(Exception error) {
                            Log.e(TAG, "🚨 USB错误", error);
                        }

                        @Override
                        public void onPermissionDenied(UsbDevice device) {
                            Log.w(TAG, "🔒 USB权限被拒绝: VID=" + device.getVendorId() + " PID=" + device.getProductId());
                        }
                    });

                    manager.setDebugMode(true, 1);
                    manager.scanDevices();
                } else {
                    Log.w(TAG, "USBSerialManager instance not available");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error accessing USBSerialManager", e);
            }
        }
    }
}
