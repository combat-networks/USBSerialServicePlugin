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
        
        USBSerialManager manager = new USBSerialManager(context);
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
        
        USBSerialManager manager = new USBSerialManager(context);
        
        // 测试不同的串口参数
        int[] baudRates = {9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600};
        int[] dataBits = {5, 6, 7, 8};
        int[] stopBits = {1, 2};
        int[] parity = {0, 1, 2}; // 0=无, 1=奇校验, 2=偶校验
        
        for (int baudRate : baudRates) {
            for (int dataBit : dataBits) {
                for (int stopBit : stopBits) {
                    for (int par : parity) {
                        manager.setSerialParameters(baudRate, dataBit, stopBit, par);
                        Log.d(TAG, String.format("设置参数: 波特率=%d, 数据位=%d, 停止位=%d, 校验位=%d", 
                              baudRate, dataBit, stopBit, par));
                    }
                }
            }
        }
        
        manager.destroy();
    }
    
    /**
     * 测试数据发送
     */
    public static void testDataSending(Context context) {
        Log.d(TAG, "开始测试数据发送...");
        
        USBSerialManager manager = new USBSerialManager(context);
        
        // 测试字符串
        String[] testStrings = {
            "Hello World!",
            "USB Serial Test",
            "中文字符测试",
            "1234567890",
            "!@#$%^&*()",
            "Line 1\nLine 2\nLine 3"
        };
        
        for (String testString : testStrings) {
            try {
                manager.sendString(testString);
                Log.d(TAG, "发送成功: " + testString);
            } catch (Exception e) {
                Log.e(TAG, "发送失败: " + testString, e);
            }
        }
        
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
