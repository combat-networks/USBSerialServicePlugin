package com.saemaps.android.usbserial.terminal;

import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialProber;

final class CustomProber {

    private CustomProber() {}

    static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x1234, 0xabcd, FtdiSerialDriver.class);
        return new UsbSerialProber(customTable);
    }
}
