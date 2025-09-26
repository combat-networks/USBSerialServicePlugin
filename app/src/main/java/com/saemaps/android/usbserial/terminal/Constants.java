package com.saemaps.android.usbserial.terminal;

import com.saemaps.android.usbserial.plugin.BuildConfig;

final public class Constants {

    private Constants() {}

    public static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    public static final String INTENT_ACTION_DISCONNECT = BuildConfig.APPLICATION_ID + ".Disconnect";
    public static final String NOTIFICATION_CHANNEL = BuildConfig.APPLICATION_ID + ".Channel";
    public static final String INTENT_CLASS_MAIN_ACTIVITY = BuildConfig.APPLICATION_ID + ".MapEntry";

    public static final int NOTIFY_MANAGER_START_FOREGROUND_SERVICE = 1001;
}
