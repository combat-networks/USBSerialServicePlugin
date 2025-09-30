package com.saemaps.android.usbserial.plugin;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;

/**
 * Activity that handles USB device attachment events.
 * This activity is launched when a USB device matching our filter is attached.
 */
public class UsbDeviceAttachedActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the USB device from the intent
        Intent intent = getIntent();
        UsbDevice device = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);

        if (device != null) {
            // Send broadcast to our receiver to handle the USB device
            Intent broadcastIntent = new Intent("com.saemaps.android.usbserial.USB_DEVICE_ATTACHED");
            broadcastIntent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device);
            sendBroadcast(broadcastIntent);
        }

        // Finish this activity immediately as it's just a trigger
        finish();
    }
}
