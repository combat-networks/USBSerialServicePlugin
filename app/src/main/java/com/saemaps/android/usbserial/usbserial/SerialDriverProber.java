package com.saemaps.android.usbserial.usbserial;

import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

/**
 * Helper providing a UsbSerialProber that includes additional vendor/product
 * IDs
 * (notably CH340/CH34x variants) beyond the library defaults.
 * 
 * 修复：避免在SAE插件环境中使用反射调用，改为直接手动构造驱动
 */
public final class SerialDriverProber {

    private static final String TAG = "SerialDriverProber";

    private SerialDriverProber() {
    }

    public static UsbSerialDriver probeDevice(UsbDevice device) {
        if (device == null) {
            Log.w(TAG, "Device is null, returning null");
            return null;
        }

        try {
            // 首先尝试默认的prober
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver != null) {
                Log.d(TAG, "Found driver via default prober for device: " + describe(device));
                return driver;
            }
        } catch (Exception e) {
            Log.w(TAG, "Default prober failed for device: " + describe(device), e);
        }

        // 如果默认prober找不到，手动检查VID/PID并直接构造驱动
        int vid = device.getVendorId();
        int pid = device.getProductId();

        Log.d(TAG, "Probing device VID=0x" + Integer.toHexString(vid) + " PID=0x" + Integer.toHexString(pid));

        try {
            // CH34x系列驱动
            if (vid == 0x1A86) {
                if (pid == 0x7523 || pid == 0x5523 || pid == 0x55D4) {
                    Log.d(TAG, "Creating Ch34xSerialDriver for device: " + describe(device));
                    try {
                        UsbSerialDriver driver = new Ch34xSerialDriver(device);
                        Log.d(TAG, "Successfully created Ch34xSerialDriver instance");
                        return driver;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to create Ch34xSerialDriver for device: " + describe(device), e);
                        return null;
                    }
                }
            }

            // FTDI系列驱动
            if (vid == 0x0403 && pid == 0x6015) {
                Log.d(TAG, "Creating FtdiSerialDriver for device: " + describe(device));
                try {
                    UsbSerialDriver driver = new FtdiSerialDriver(device);
                    Log.d(TAG, "Successfully created FtdiSerialDriver instance");
                    return driver;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create FtdiSerialDriver for device: " + describe(device), e);
                    return null;
                }
            }

            Log.v(TAG, "No matching driver found for device: " + describe(device));
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in probeDevice for device: " + describe(device), e);
            return null;
        }
    }

    private static String describe(UsbDevice device) {
        return "VID=0x" + Integer.toHexString(device.getVendorId()) +
                " PID=0x" + Integer.toHexString(device.getProductId()) +
                " " + device.getDeviceName();
    }
}
