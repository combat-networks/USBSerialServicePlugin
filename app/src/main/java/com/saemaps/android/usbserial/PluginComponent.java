package com.saemaps.android.usbserial;

import com.saemaps.android.usbserial.PlatformProxy;

public class PluginComponent {

    // 为了方便管理，这里采用单例模式，所以必须实现 getInstance 方法，虽然接口上没有，但会被平台通过反射调用
    private static PluginComponent instance;

    public static synchronized PluginComponent getInstance() {
        if (instance == null) {
            instance = new PluginComponent();
        }
        return instance;
    }

    public static void setPlatformInstance(Class<?> clazz) {
        PlatformProxy.getInstance(clazz);
    }

    public static boolean isInit() {
        return instance != null;
    }

    public String getDescription() {
        return "USB Serial Communication Plugin";
    }

}
