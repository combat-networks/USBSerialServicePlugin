
package com.saemaps.android.usbserial;

import android.content.Context;
import android.content.Intent;

import com.saemaps.android.ipc.AtakBroadcast;
import com.saemaps.android.ipc.AtakBroadcast.DocumentedIntentFilter;

import com.saemaps.android.maps.MapView;
import com.saemaps.android.dropdown.DropDownMapComponent;

import com.saemaps.android.usbserial.PluginComponent;
import com.saemaps.coremap.log.Log;

public class USBSerialMapComponent extends DropDownMapComponent {

    private static final String TAG = "USBSerialMapComponent";

    public static final String PREFIX = "com.saemaps.android.usbserial.USBSerialDropDownReceiver";
    // 用于注册机器学习框架的模型插件
    public static final String REGISTER_MODEL_PLUGIN = PREFIX + ".REGISTER_MODEL_PLUGIN";
    public static final String UNREGISTER_MODEL_PLUGIN = PREFIX + ".UNREGISTER_MODEL_PLUGIN";

    private Context pluginContext;

    private USBSerialDropDownReceiver ddr;

    public void onCreate(final Context context, Intent intent,
            final MapView view) {

        // context.setTheme(R.style.ATAKPluginTheme); // Theme not available
        super.onCreate(context, intent, view);
        pluginContext = context;

        Log.d(TAG, "USBSerialMapComponent onCreate called - DEBUG");
        System.out.println("USBSerialMapComponent onCreate called - SYSTEM OUT");

        Log.d(TAG, "context is null: " + (context == null));
        Log.d(TAG, "view is null: " + (view == null));

        ddr = new USBSerialDropDownReceiver(
                view, context);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(USBSerialDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        Log.d(TAG, "USBSerialMapComponent registration completed - DEBUG");
        System.out.println("USBSerialMapComponent registration completed - SYSTEM OUT");

        new Thread(() -> {
            while (!PluginComponent.isInit()) {
                Intent register = new Intent(REGISTER_MODEL_PLUGIN);
                register.putExtra("info", PluginComponent.class);
                AtakBroadcast.getInstance().sendBroadcast(register);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }

    @Override
    public void onResume(Context context, MapView view) {
        super.onResume(context, view);
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        super.onDestroyImpl(context, view);
        Intent unregister = new Intent(UNREGISTER_MODEL_PLUGIN);
        unregister.putExtra("info", PluginComponent.class);
        AtakBroadcast.getInstance().sendBroadcast(unregister);
    }

}
