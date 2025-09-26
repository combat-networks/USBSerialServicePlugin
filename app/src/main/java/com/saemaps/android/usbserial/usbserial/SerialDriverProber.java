package com.saemaps.android.usbserial.usbserial;

import android.hardware.usb.UsbDevice;

import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

/**
 * Helper providing a UsbSerialProber that includes additional vendor/product IDs
 * (notably CH340/CH34x variants) beyond the library defaults.
 */
public final class SerialDriverProber {

    private SerialDriverProber() {
    }

    private static UsbSerialProber customProber;

    private static UsbSerialProber getCustomProber() {
        if (customProber == null) {
            ProbeTable customTable = new ProbeTable();
            // Qinheng / WCH CH340 and family (covers variants commonly missing on some Android builds)
            customTable.addProduct(0x1A86, 0x7523, Ch34xSerialDriver.class); // CH340/CH341
            customTable.addProduct(0x1A86, 0x5523, Ch34xSerialDriver.class); // CH341A programmer
            customTable.addProduct(0x1A86, 0x55D4, Ch34xSerialDriver.class); // CH9102F
            // Example of FTDI devices with custom VID/PID sometimes absent from default table
            customTable.addProduct(0x0403, 0x6015, FtdiSerialDriver.class); // FT231X (defensive)
            customProber = new UsbSerialProber(customTable);
        }
        return customProber;
    }

    public static UsbSerialDriver probeDevice(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = getCustomProber().probeDevice(device);
        }
        return driver;
    }
}
