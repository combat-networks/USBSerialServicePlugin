package com.saemaps.android.usbserial.usbserial;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import java.util.List;

/**
 * USB串口功能测试类
 * 用于验证USB串口管理器的基本功能
 */
public class USBSerialTest {

    private static final String TAG = "USBSerialTest";

    /**
     * 测试USB设备扫描
     */
    public static void testDeviceScan(Context context) {
        Log.d(TAG, "开始测试USB设备扫描...");

        USBSerialManager manager = new USBSerialManager(context, context);
        manager.setListener(new USBSerialManager.USBSerialListener() {
            @Override
            public void onDeviceDetected(List<UsbDevice> devices) {
                Log.d(TAG, "检测到 " + devices.size() + " 个USB设备");
                for (UsbDevice device : devices) {
                    Log.d(TAG, "设备: " + device.getDeviceName() +
                            " (VID:" + device.getVendorId() +
                            ", PID:" + device.getProductId() + ")");
                }
            }

            @Override
            public void onDeviceConnected(UsbDevice device) {
                Log.d(TAG, "设备已连接: " + device.getDeviceName());
            }

            @Override
            public void onDeviceDisconnected() {
                Log.d(TAG, "设备已断开");
            }

            @Override
            public void onDataReceived(byte[] data) {
                Log.d(TAG, "接收到数据: " + new String(data));
            }

            @Override
            public void onError(Exception error) {
                Log.e(TAG, "USB错误", error);
            }

            @Override
            public void onPermissionDenied(UsbDevice device) {
                Log.w(TAG, "USB权限被拒绝: " + device.getDeviceName());
            }
        });

        // 扫描设备
        manager.scanDevices();

        // 清理
        manager.destroy();
    }

    /**
     * 测试串口参数设置
     */
    public static void testSerialParameters(Context context) {
        Log.d(TAG, "开始测试串口参数设置...");

        USBSerialManager manager = new USBSerialManager(context, context);

        // TODO: 串口参数设置功能将在下一阶段实现
        Log.d(TAG, "串口参数设置功能暂未实现，将在下一阶段添加");

        manager.destroy();
    }

    /**
     * 测试数据发送
     */
    public static void testDataSending(Context context) {
        Log.d(TAG, "开始测试数据发送...");

        USBSerialManager manager = new USBSerialManager(context, context);

        // TODO: 数据发送功能将在下一阶段实现
        Log.d(TAG, "数据发送功能暂未实现，将在下一阶段添加");

        manager.destroy();
    }

    /**
     * 运行所有测试
     */
    public static void runAllTests(Context context) {
        Log.d(TAG, "========== 开始USB串口功能测试 ==========");

        testDeviceScan(context);
        testSerialParameters(context);
        testDataSending(context);

        Log.d(TAG, "========== USB串口功能测试完成 ==========");
    }
}
