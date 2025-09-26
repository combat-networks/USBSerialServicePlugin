
package com.saemaps.android.usbserial;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

import com.saemaps.android.maps.MapComponent;
import com.saemaps.android.maps.MapView;
import com.saemaps.android.usbserial.USBSerialMapComponent;

import transapps.maps.plugin.lifecycle.Lifecycle;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import com.saemaps.coremap.log.Log;

public class USBSerialLifecycle implements Lifecycle {

    private final Context pluginContext;
    private final Collection<MapComponent> overlays;
    private MapView mapView;

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

        // create components
        Iterator<MapComponent> iter = USBSerialLifecycle.this.overlays
                .iterator();
        MapComponent c;
        while (iter.hasNext()) {
            c = iter.next();
            try {
                c.onCreate(USBSerialLifecycle.this.pluginContext,
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
}
