package com.saemaps.android.usbserial;

import com.saemaps.android.ipc.AtakBroadcast;
import com.saemaps.android.usbserial.USBSerialDropDownReceiver;
import com.saemaps.android.usbserial.plugin.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import transapps.mapi.MapView;
import transapps.maps.plugin.tool.Group;
import transapps.maps.plugin.tool.Tool;
import transapps.maps.plugin.tool.ToolDescriptor;

public class USBSerialTool extends Tool implements ToolDescriptor {

    private static final String TAG = "USBSerialTool";
    private final Context context;

    public USBSerialTool(Context context) {
        this.context = context;
    }

    @Override
    public String getDescription() {
        return context.getString(R.string.app_name);
    }

    @Override
    public Drawable getIcon() {
        return (context == null) ? null
                : context.getResources().getDrawable(R.drawable.ic_launcher);
    }

    @Override
    public Group[] getGroups() {
        return new Group[] {
                Group.GENERAL
        };
    }

    @Override
    public String getShortDescription() {
        return context.getString(R.string.app_name);
    }

    @Override
    public Tool getTool() {
        return this;
    }

    @Override
    public void onActivate(Activity arg0, MapView arg1, ViewGroup arg2,
            Bundle arg3,
            ToolCallback arg4) {

        Log.d(TAG, "USBSerialTool onActivate called");

        // Hack to close the dropdown that automatically opens when a tool
        // plugin is activated.
        if (arg4 != null) {
            Log.d(TAG, "Calling onToolDeactivated to close auto-opened dropdown");
            arg4.onToolDeactivated(this);
        }

        // Send broadcast to show the dropdown
        Log.d(TAG, "Sending SHOW_PLUGIN broadcast");
        Intent i = new Intent(USBSerialDropDownReceiver.SHOW_PLUGIN);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    @Override
    public void onDeactivate(ToolCallback arg0) {
    }
}
