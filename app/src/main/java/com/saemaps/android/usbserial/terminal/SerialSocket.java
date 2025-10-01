package com.saemaps.android.usbserial.terminal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.security.InvalidParameterException;

public class SerialSocket implements SerialInputOutputManager.Listener {

    private static final int WRITE_WAIT_MILLIS = 200;
    private static final String TAG = "SerialSocket";

    private final BroadcastReceiver disconnectReceiver;
    private final Context context;
    private SerialListener listener;
    private UsbDeviceConnection connection;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;

    SerialSocket(Context context, UsbDeviceConnection connection, UsbSerialPort serialPort) {
        if (context instanceof Activity) {
            throw new InvalidParameterException("expected non UI context");
        }
        this.context = context.getApplicationContext();
        this.connection = connection;
        this.serialPort = serialPort;
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (listener != null) {
                    listener.onSerialIoError(new IOException("background disconnect"));
                }
                disconnect();
            }
        };
    }

    public String getName() {
        return serialPort.getDriver().getClass().getSimpleName().replace("SerialDriver", "");
    }

    public void connect(SerialListener listener) throws IOException {
        this.listener = listener;
        context.registerReceiver(disconnectReceiver, new IntentFilter(Constants.INTENT_ACTION_DISCONNECT));
        try {
            serialPort.setDTR(true); // for arduino, ...
            serialPort.setRTS(true);
        } catch (UnsupportedOperationException e) {
            Log.d(TAG, "Failed to set initial DTR/RTS", e);
        }
        ioManager = new SerialInputOutputManager(serialPort, this);
        // 使用3.8.0版本推荐的启动方式
        Log.d(TAG, "Using Thread-based SerialInputOutputManager - 3.8.0 API");
        new Thread(ioManager, "SerialInputOutputManager").start();
    }

    public void disconnect() {
        listener = null; // ignore remaining data and errors
        if (ioManager != null) {
            ioManager.setListener(null);
            // 使用3.9.0版本推荐的停止方式
            try {
                ioManager.stop(); // 3.9.0版本推荐使用stop()方法
            } catch (NoSuchMethodError e) {
                // 如果stop()方法不存在，忽略（向后兼容）
                Log.d(TAG, "Using legacy SerialInputOutputManager stop behavior");
            }
            ioManager = null;
        }
        if (serialPort != null) {
            try {
                serialPort.setDTR(false);
                serialPort.setRTS(false);
            } catch (Exception ignored) {
            }
            try {
                serialPort.close();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        try {
            context.unregisterReceiver(disconnectReceiver);
        } catch (Exception ignored) {
        }
    }

    public void write(byte[] data) throws IOException {
        if (serialPort == null)
            throw new IOException("not connected");
        serialPort.write(data, WRITE_WAIT_MILLIS);
    }

    @Override
    public void onNewData(byte[] data) {
        if (listener != null) {
            listener.onSerialRead(data);
        }
    }

    @Override
    public void onRunError(Exception e) {
        if (listener != null) {
            listener.onSerialIoError(e);
        }
    }
}
