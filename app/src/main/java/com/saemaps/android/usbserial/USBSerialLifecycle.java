
package com.saemaps.android.usbserial;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

import com.saemaps.android.maps.MapComponent;
import com.saemaps.android.maps.MapView;
import com.saemaps.android.usbserial.USBSerialMapComponent;
import com.saemaps.android.usbserial.usbserial.USBSerialManager;

import transapps.maps.plugin.lifecycle.Lifecycle;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import com.saemaps.coremap.log.Log;

public class USBSerialLifecycle implements Lifecycle {

    private final Context pluginContext;
    private final Collection<MapComponent> overlays;
    private MapView mapView;

    // 🔑 静态USBSerialManager实例，供USBSerialPermissionReceiver访问
    private static USBSerialManager sUsbSerialManagerInstance;

    private final static String TAG = "USBSerialLifecycle";

    public USBSerialLifecycle(Context ctx) {
        this.pluginContext = ctx;
        this.overlays = new LinkedList<>();
        this.mapView = null;
        // PluginNativeLoader.init(ctx); // 已删除，不再需要

        Log.d(TAG, "USBSerialLifecycle constructor called - DEBUG");
        System.out.println("USBSerialLifecycle constructor called - SYSTEM OUT");
    }

    @Override
    public void onConfigurationChanged(Configuration arg0) {
        for (MapComponent c : this.overlays)
            c.onConfigurationChanged(arg0);
    }

    @Override
    public void onCreate(final Activity arg0,
            final transapps.mapi.MapView arg1) {
        Log.d(TAG, "USBSerialLifecycle onCreate called - DEBUG");
        System.out.println("USBSerialLifecycle onCreate called - SYSTEM OUT");

        if (arg1 == null || !(arg1.getView() instanceof MapView)) {
            Log.w(TAG, "This plugin is only compatible with SAE MapView");
            System.out.println("USBSerialLifecycle: This plugin is only compatible with SAE MapView - SYSTEM OUT");
            return;
        }
        this.mapView = (MapView) arg1.getView();
        USBSerialLifecycle.this.overlays
                .add(new USBSerialMapComponent());

        // 🔑 在生命周期中创建并注册 USBSerialManager，确保使用宿主应用的 ApplicationContext
        try {
            Context pluginCtx = USBSerialLifecycle.this.pluginContext;
            Context hostAppCtx = arg0 != null ? arg0.getApplicationContext() : null;
            if (pluginCtx != null && hostAppCtx != null) {
                USBSerialManager manager = new USBSerialManager(pluginCtx, hostAppCtx);
                manager.setMapView(USBSerialLifecycle.this.mapView);
                setUsbSerialManagerInstance(manager);
                Log.d(TAG, "Setting USBSerialManager instance: not null");
            } else {
                Log.w(TAG, "Unable to initialize USBSerialManager: plugin or host context is null");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize USBSerialManager in Lifecycle", e);
        }

        // create components
        Iterator<MapComponent> iter = USBSerialLifecycle.this.overlays
                .iterator();
        MapComponent c;
        while (iter.hasNext()) {
            c = iter.next();
            try {
                Log.d(TAG, "pluginContext is null: " + (USBSerialLifecycle.this.pluginContext == null));
                Log.d(TAG, "arg0 context: " + (arg0 != null ? arg0.getApplicationContext() : "null"));

                // 使用插件上下文创建 MapComponent
                Context contextToUse = USBSerialLifecycle.this.pluginContext != null
                        ? USBSerialLifecycle.this.pluginContext
                        : arg0.getApplicationContext();

                c.onCreate(contextToUse,
                        arg0.getIntent(),
                        USBSerialLifecycle.this.mapView);
            } catch (Exception e) {
                Log.w(TAG,
                        "Unhandled exception trying to create overlays MapComponent",
                        e);
                iter.remove();
            }
        }
    }

    @Override
    public void onDestroy() {
        for (MapComponent c : this.overlays)
            c.onDestroy(this.pluginContext, this.mapView);

        // 🔑 清理USBSerialManager实例
        if (sUsbSerialManagerInstance != null) {
            Log.d(TAG, "Destroying USBSerialManager instance");
            sUsbSerialManagerInstance.destroy();
            sUsbSerialManagerInstance = null;
        }
    }

    @Override
    public void onFinish() {
        // XXX - no corresponding MapComponent method
    }

    @Override
    public void onPause() {
        for (MapComponent c : this.overlays)
            c.onPause(this.pluginContext, this.mapView);
    }

    @Override
    public void onResume() {
        for (MapComponent c : this.overlays)
            c.onResume(this.pluginContext, this.mapView);
    }

    @Override
    public void onStart() {
        for (MapComponent c : this.overlays)
            c.onStart(this.pluginContext, this.mapView);
    }

    @Override
    public void onStop() {
        for (MapComponent c : this.overlays)
            c.onStop(this.pluginContext, this.mapView);
    }

    /**
     * 🔑 设置USBSerialManager实例，供USBSerialPermissionReceiver访问
     * 
     * @param manager USBSerialManager实例
     */
    public static void setUsbSerialManagerInstance(USBSerialManager manager) {
        Log.d(TAG, "Setting USBSerialManager instance: " + (manager != null ? "not null" : "null"));
        sUsbSerialManagerInstance = manager;
    }

    /**
     * 🔑 获取USBSerialManager实例，供USBSerialPermissionReceiver访问
     * 
     * @return USBSerialManager实例，如果未设置则返回null
     */
    public static USBSerialManager getUsbSerialManagerInstance() {
        Log.d(TAG, "Getting USBSerialManager instance: " + (sUsbSerialManagerInstance != null ? "not null" : "null"));
        return sUsbSerialManagerInstance;
    }
}
