package com.saemaps.android.usbserial;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.saemaps.android.dropdown.DropDown.OnStateListener;
import com.saemaps.android.dropdown.DropDownReceiver;
import com.saemaps.android.maps.MapView;
import com.saemaps.android.usbserial.usbserial.SerialDriverProber;
import com.saemaps.android.usbserial.usbserial.USBSerialManager;
import com.saemaps.android.usbserial.usbserial.USBSerialManager.USBSerialListener;
import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal diagnostic drop-down allowing scan/connect/test write for USB serial devices.
 */
public class USBSerialDropDownReceiver extends DropDownReceiver implements OnStateListener, USBSerialListener {

    public static final String SHOW_PLUGIN = "com.saemaps.android.usbserial.SHOW_PLUGIN";

    private final View rootView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context pluginContext;

    private final List<UsbDevice> discoveredDevices = new ArrayList<>();
    private final USBSerialManager serialManager;

    private TextView tvStatus;
    private TextView tvDevices;
    private TextView tvLog;
    private ScrollView logScroll;

    public USBSerialDropDownReceiver(MapView mapView, Context context) {
        super(mapView);
        this.pluginContext = context;
        // rootView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
        rootView = new LinearLayout(context);
        initViews();
        serialManager = new USBSerialManager(context.getApplicationContext());
        serialManager.setListener(this);
    }

    private void initViews() {
        // tvStatus = rootView.findViewById(R.id.tv_status);
        // tvDevices = rootView.findViewById(R.id.tv_devices);
        // tvLog = rootView.findViewById(R.id.tv_log);
        // logScroll = rootView.findViewById(R.id.log_scroll);

        // Button btnScan = rootView.findViewById(R.id.btn_scan);
        // Button btnConnect = rootView.findViewById(R.id.btn_connect_first);
        // Button btnDisconnect = rootView.findViewById(R.id.btn_disconnect);
        // Button btnSend = rootView.findViewById(R.id.btn_send_test);

        // btnScan.setOnClickListener(v -> {
        //     appendLog("Scanning for USB serial devices...");
        //     serialManager.scanDevices();
        // });
        // btnConnect.setOnClickListener(v -> {
        //     if (discoveredDevices.isEmpty()) {
        //         appendLog("No devices available to connect.");
        //     } else {
        //         UsbDevice device = discoveredDevices.get(0);
        //         appendLog("Attempting to connect: VID=" + device.getVendorId() + " PID=" + device.getProductId());
        //         serialManager.connectToDevice(device);
        //     }
        // });
        // btnDisconnect.setOnClickListener(v -> {
        //     appendLog("Disconnect requested.");
        //     serialManager.disconnect();
        // });
        // btnSend.setOnClickListener(v -> {
        //     if (!serialManager.isConnected()) {
        //         appendLog("Cannot send data: no active connection.");
        //         return;
        //     }
        //     try {
        //         String payload = "USBSerialPlugin:TEST\r\n";
        //         serialManager.sendString(payload);
        //         appendLog("Sent test payload: " + payload.trim());
        //     } catch (IOException e) {
        //         appendLog("Send failed: " + e.getMessage());
        //     }
        // });
    }

    @Override
    public void disposeImpl() {
        serialManager.destroy();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (SHOW_PLUGIN.equals(intent.getAction())) {
            showDropDown(rootView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
            serialManager.scanDevices();
        }
    }

    @Override
    public void onDropDownSelectionRemoved() { }

    @Override
    public void onDropDownVisible(boolean v) { }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownClose() {
        // keep manager alive for background monitoring
    }

    // === USBSerialListener implementation ===

    @Override
    public void onDeviceDetected(List<UsbDevice> devices) {
        discoveredDevices.clear();
        discoveredDevices.addAll(devices);
        mainHandler.post(() -> {
            if (devices.isEmpty()) {
                tvDevices.setText("No matching USB serial devices detected.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < devices.size(); i++) {
                    UsbDevice device = devices.get(i);
                    UsbSerialDriver driver = SerialDriverProber.probeDevice(device);
                    sb.append(i + 1).append('.').append(' ')
                      .append("VID=").append(device.getVendorId())
                      .append(" PID=").append(device.getProductId());
                    if (driver != null) {
                        sb.append(" driver=").append(driver.getClass().getSimpleName());
                    }
                    if (i < devices.size() - 1) {
                        sb.append('\n');
                    }
                }
                tvDevices.setText(sb.toString());
            }
            appendLog("Scan finished: " + devices.size() + " candidate device(s).");
        });
    }

    @Override
    public void onDeviceConnected(UsbDevice device) {
        mainHandler.post(() -> {
            tvStatus.setText("Status: connected VID=" + device.getVendorId() + " PID=" + device.getProductId());
            appendLog("Connected to " + device.getDeviceName());
        });
    }

    @Override
    public void onDeviceDisconnected() {
        mainHandler.post(() -> {
            tvStatus.setText("Status: disconnected");
            appendLog("Serial link closed.");
        });
    }

    @Override
    public void onDataReceived(byte[] data) {
        mainHandler.post(() -> appendLog("RX: " + bytesToDisplayString(data)));
    }

    @Override
    public void onError(Exception error) {
        mainHandler.post(() -> appendLog("Error: " + (error != null ? error.getMessage() : "unknown")));
    }

    @Override
    public void onPermissionDenied(UsbDevice device) {
        mainHandler.post(() -> appendLog("USB permission denied for VID=" + device.getVendorId() + " PID=" + device.getProductId()));
    }

    private void appendLog(String message) {
        String current = tvLog.getText().toString();
        if ("Waiting...".contentEquals(current) || current.isEmpty()) {
            tvLog.setText(message);
        } else {
            tvLog.append("\n" + message);
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String bytesToDisplayString(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int ch = b & 0xFF;
            if (ch >= 32 && ch <= 126) {
                sb.append((char) ch);
            } else {
                sb.append(String.format("\\x%02X", ch));
            }
        }
        return sb.toString();
    }
}
