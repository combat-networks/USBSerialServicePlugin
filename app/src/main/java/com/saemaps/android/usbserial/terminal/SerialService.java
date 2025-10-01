package com.saemaps.android.usbserial.terminal;

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
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.saemaps.android.usbserial.plugin.R;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Mirror of SimpleUsbTerminal SerialService adapted to plugin package.
 */
public class SerialService extends Service implements SerialListener {

    public class SerialBinder extends Binder {
        public SerialService getService() {
            return SerialService.this;
        }
    }

    private enum QueueType {
        Connect, ConnectError, Read, IoError
    }

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
            if (type == QueueType.Read)
                init();
        }

        QueueItem(QueueType type, ArrayDeque<byte[]> datas) {
            this.type = type;
            this.datas = datas;
            this.error = null;
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
    private final QueueItem lastRead = new QueueItem(QueueType.Read);

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

    // === API ===

    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
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
        if (!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    public void attach(SerialListener listener) {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            throw new IllegalArgumentException("not in main thread");
        }
        initNotification();
        cancelNotification();
        synchronized (this) {
            this.listener = listener;
        }
        for (QueueItem item : queue1)
            dispatch(item, listener);
        for (QueueItem item : queue2)
            dispatch(item, listener);
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if (connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end
        // up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    // === notification ===

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service",
                    NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE;
    }

    private void createNotification() {
        Intent disconnectIntent = new Intent()
                .setPackage(getPackageName())
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to " + socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect",
                        disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset
        // using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both
        // drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    private void dispatch(QueueItem item, SerialListener target) {
        switch (item.type) {
            case Connect:
                target.onSerialConnect();
                break;
            case ConnectError:
                target.onSerialConnectError(item.error);
                break;
            case Read:
                target.onSerialRead(item.datas);
                break;
            case IoError:
                target.onSerialIoError(item.error);
                break;
        }
    }

    // === SerialListener ===

    @Override
    public void onSerialConnect() {
        if (!connected)
            return;
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null)
                        listener.onSerialConnect();
                    else
                        queue1.add(new QueueItem(QueueType.Connect));
                });
            } else {
                queue2.add(new QueueItem(QueueType.Connect));
            }
        }
    }

    @Override
    public void onSerialConnectError(Exception e) {
        if (!connected)
            return;
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null)
                        listener.onSerialConnectError(e);
                    else {
                        queue1.add(new QueueItem(QueueType.ConnectError, e));
                        disconnect();
                    }
                });
            } else {
                queue2.add(new QueueItem(QueueType.ConnectError, e));
                disconnect();
            }
        }
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        // 处理批量数据读取 - 将ArrayDeque中的数据逐个处理
        if (!connected || datas == null || datas.isEmpty()) {
            return;
        }

        synchronized (this) {
            if (listener != null) {
                // 如果有监听器，直接转发数据
                mainLooper.post(() -> {
                    if (listener != null) {
                        listener.onSerialRead(datas);
                    } else {
                        queue1.add(new QueueItem(QueueType.Read, datas));
                    }
                });
            } else {
                // 如果没有监听器，将数据加入队列
                queue2.add(new QueueItem(QueueType.Read, datas));
            }
        }
    }

    @Override
    public void onSerialRead(byte[] data) {
        if (!connected)
            return;
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
                        if (listener != null)
                            listener.onSerialRead(datas);
                        else
                            queue1.add(new QueueItem(QueueType.Read, datas));
                    });
                }
            } else {
                if (queue2.isEmpty() || queue2.getLast().type != QueueType.Read)
                    queue2.add(new QueueItem(QueueType.Read));
                queue2.getLast().add(data);
            }
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        if (!connected)
            return;
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null)
                        listener.onSerialIoError(e);
                    else {
                        queue1.add(new QueueItem(QueueType.IoError, e));
                        disconnect();
                    }
                });
            } else {
                queue2.add(new QueueItem(QueueType.IoError, e));
                disconnect();
            }
        }
    }
}
