package com.saemaps.android.usbserial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.saemaps.android.usbserial.usbserial.USBSerialManager;

/**
 * USB权限广播接收器
 * 处理USB设备权限请求的结果
 */
public class USBSerialPermissionReceiver extends BroadcastReceiver {

    private static final String TAG = "USBSerialPermissionReceiver";
    // 确保 ACTION_USB_PERMISSION 与 USBSerialManager 中的定义一致
    private static final String ACTION_USB_PERMISSION = "com.saemaps.android.usbserial.plugin.GRANT_USB";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();
            Log.d(TAG, "🔐 Received broadcast: " + action);

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                    if (device == null) {
                        Log.e(TAG, "⚠️ Permission receiver: device is null!");
                        return;
                    }

                    Log.d(TAG, "🔐 Processing permission result for device: " + describeDevice(device) +
                            " granted=" + granted);

                    // 🔑 获取USBSerialManager实例 - 通过USBSerialLifecycle获取
                    USBSerialManager manager = USBSerialManager.getInstance();
                    if (manager != null) {
                        Log.d(TAG, "✅ Found USBSerialManager instance, handling permission result directly");
                        if (granted) {
                            Log.d(TAG, "✅ USB permission granted for device: " + describeDevice(device));
                            // 直接调用USBSerialManager的方法处理权限授予
                            manager.openPortAfterPermission(device);
                        } else {
                            Log.w(TAG, "❌ USB permission denied for device: " + describeDevice(device));
                            // 通知监听器权限被拒绝
                            if (manager.getListener() != null) {
                                manager.getListener().onPermissionDenied(device);
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ USBSerialManager instance not found, using fallback broadcast mechanism");
                        // 🔑 作为备选方案，发送广播通知
                        // 这种情况可能发生在插件还未完全初始化时
                        if (granted) {
                            Log.d(TAG, "📡 Broadcasting permission granted for device: " + describeDevice(device));
                            notifyPermissionGranted(context, device);
                        } else {
                            Log.w(TAG, "📡 Broadcasting permission denied for device: " + describeDevice(device));
                            notifyPermissionDenied(context, device);
                        }
                    }
                }
            } else {
                Log.d(TAG, "🔐 Ignoring broadcast action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception in permission receiver", e);
        }
    }

    /**
     * 辅助方法，用于描述设备信息
     */
    private String describeDevice(UsbDevice device) {
        if (device == null)
            return "null device";
        return String.format("VID=%04X PID=%04X", device.getVendorId(), device.getProductId());
    }

    /**
     * 通知权限授予
     */
    private void notifyPermissionGranted(Context context, UsbDevice device) {
        // 发送广播通知USBSerialManager权限已授予
        Intent grantedIntent = new Intent("com.saemaps.android.USB_PERMISSION_GRANTED");
        grantedIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
        context.sendBroadcast(grantedIntent);
        Log.d(TAG, "🔐 Sent permission granted broadcast for device: VID=" + device.getVendorId() + " PID="
                + device.getProductId());
    }

    /**
     * 通知权限被拒绝
     */
    private void notifyPermissionDenied(Context context, UsbDevice device) {
        // 发送广播通知USBSerialManager权限被拒绝
        Intent deniedIntent = new Intent("com.saemaps.android.USB_PERMISSION_DENIED");
        deniedIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
        context.sendBroadcast(deniedIntent);
        Log.d(TAG, "🔐 Sent permission denied broadcast for device: VID=" + device.getVendorId() + " PID="
                + device.getProductId());
    }
}
