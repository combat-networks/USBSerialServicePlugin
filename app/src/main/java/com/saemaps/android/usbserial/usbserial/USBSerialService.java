package com.saemaps.android.usbserial.usbserial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.saemaps.android.usbserial.USBSerialDropDownReceiver;
import com.saemaps.android.usbserial.plugin.R;
import com.saemaps.android.usbserial.terminal.Constants;
import com.saemaps.android.usbserial.terminal.SerialListener;
import com.saemaps.android.usbserial.terminal.SerialSocket;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Foreground USB serial service bridging USB driver sockets with plugin UI.
 */
public class USBSerialService extends Service implements SerialListener {

    public class SerialBinder extends Binder {
        public USBSerialService getService() {
            return USBSerialService.this;
        }
    }

    private enum QueueType { CONNECT, CONNECT_ERROR, READ, IO_ERROR }

    private static class QueueItem {
        final QueueType type;
        ArrayDeque<byte[]> datas;
        final Exception error;

        QueueItem(QueueType type) {
            this(type, (Exception) null);
        }

        QueueItem(QueueType type, Exception error) {
            this.type = type;
            this.error = error;
            if (type == QueueType.READ) {
                init();
            }
        }

        QueueItem(QueueType type, ArrayDeque<byte[]> datas) {
            this.type = type;
            this.error = null;
            this.datas = datas;
        }

        void init() {
            datas = new ArrayDeque<>();
        }

        void add(byte[] data) {
            datas.add(data);
        }
    }

    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final IBinder binder = new SerialBinder();
    private final ArrayDeque<QueueItem> queue1 = new ArrayDeque<>();
    private final ArrayDeque<QueueItem> queue2 = new ArrayDeque<>();
    private final QueueItem lastRead = new QueueItem(QueueType.READ);

    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ===== Public API =====

    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
        initNotification();
    }

    public void disconnect() {
        connected = false;
        cancelNotification();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    public void write(byte[] data) throws IOException {
        if (!connected) {
            throw new IOException("not connected");
        }
        socket.write(data);
    }

    public void attach(SerialListener listener) {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            throw new IllegalArgumentException("attach must be invoked on main thread");
        }
        cancelNotification();
        synchronized (this) {
            this.listener = listener;
        }
        for (QueueItem item : queue1) {
            dispatchQueued(item, listener);
        }
        for (QueueItem item : queue2) {
            dispatchQueued(item, listener);
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            throw new IllegalArgumentException("detach must be invoked on main thread");
        }
        synchronized (this) {
            listener = null;
        }
        initNotification();
    }

    // ===== Notification handling =====

    private void initNotification() {
        if (!connected) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_description));
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildNotification();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private Notification buildNotification() {
        Intent disconnectIntent = new Intent(Constants.INTENT_ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                disconnectIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent showIntent = new Intent(this, USBSerialDropDownReceiver.class);
        showIntent.setAction(USBSerialDropDownReceiver.SHOW_PLUGIN);
        PendingIntent showPendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                showIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(socket != null ? getString(R.string.notification_connected, socket.getName()) : getString(R.string.notification_service_running))
                .setContentIntent(showPendingIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_clear_white_24dp, getString(R.string.notification_action_disconnect), disconnectPendingIntent)
                .build();
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    private void dispatchQueued(QueueItem item, SerialListener target) {
        switch (item.type) {
            case CONNECT:
                target.onSerialConnect();
                break;
            case CONNECT_ERROR:
                target.onSerialConnectError(item.error);
                break;
            case READ:
                target.onSerialRead(item.datas);
                break;
            case IO_ERROR:
                target.onSerialIoError(item.error);
                break;
        }
    }

    // ===== SerialListener implementation =====

    @Override
    public void onSerialConnect() {
        if (!connected) {
            return;
        }
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null) {
                        listener.onSerialConnect();
                    } else {
                        queue1.add(new QueueItem(QueueType.CONNECT));
                    }
                });
            } else {
                queue2.add(new QueueItem(QueueType.CONNECT));
            }
        }
    }

    @Override
    public void onSerialConnectError(Exception e) {
        if (!connected) {
            return;
        }
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null) {
                        listener.onSerialConnectError(e);
                    } else {
                        queue1.add(new QueueItem(QueueType.CONNECT_ERROR, e));
                        disconnect();
                    }
                });
            } else {
                queue2.add(new QueueItem(QueueType.CONNECT_ERROR, e));
                disconnect();
            }
        }
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onSerialRead(byte[] data) {
        if (!connected) {
            return;
        }
        synchronized (this) {
            if (listener != null) {
                boolean first;
                synchronized (lastRead) {
                    first = lastRead.datas.isEmpty();
                    lastRead.add(data);
                }
                if (first) {
                    mainLooper.post(() -> {
                        ArrayDeque<byte[]> datas;
                        synchronized (lastRead) {
                            datas = lastRead.datas;
                            lastRead.init();
                        }
                        if (listener != null) {
                            listener.onSerialRead(datas);
                        } else {
                            queue1.add(new QueueItem(QueueType.READ, datas));
                        }
                    });
                }
            } else {
                if (queue2.isEmpty() || queue2.getLast().type != QueueType.READ) {
                    queue2.add(new QueueItem(QueueType.READ));
                }
                queue2.getLast().add(data);
            }
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        if (!connected) {
            return;
        }
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null) {
                        listener.onSerialIoError(e);
                    } else {
                        queue1.add(new QueueItem(QueueType.IO_ERROR, e));
                        disconnect();
                    }
                });
            } else {
                queue2.add(new QueueItem(QueueType.IO_ERROR, e));
                disconnect();
            }
        }
    }
}