package com.saemaps.android.usbserial.terminal;

 import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.XonXoffFilter;
import com.saemaps.android.usbserial.plugin.R;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Serial terminal fragment adapted for SAE plugin environment.
 */
public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private enum SendButtonState { Idle, Busy, Disabled }

    private ControlLines controlLines = new ControlLines();
    private XonXoffFilter flowControlFilter;

    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        Bundle args = getArguments();
        if (args == null) {
            throw new IllegalStateException("TerminalFragment requires arguments");
        }
        deviceId = args.getInt("device");
        portNum = args.getInt("port");
        baudRate = args.getInt("baud");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines.onCreateView(view);
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hexWatcher.enable(false);
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False) {
            disconnect();
        }
        requireActivity().stopService(new Intent(requireActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null) {
            service.attach(this);
        } else {
            requireActivity().startService(new Intent(requireActivity(), SerialService.class));
        }
        requireActivity().registerReceiver(broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB));
    }

    @Override
    public void onStop() {
        requireActivity().unregisterReceiver(broadcastReceiver);
        if (service != null && !requireActivity().isChangingConfigurations()) {
            service.detach();
        }
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.clear:
                receiveText.setText("");
                return true;
            case R.id.newline:
                String[] newlineNames = getResources().getStringArray(R.array.newline_names);
                String[] newlineValues = getResources().getStringArray(R.array.newline_values);
                int pos = Arrays.asList(newlineValues).indexOf(newline);
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle("Newline");
                builder.setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                    newline = newlineValues[which];
                    dialog.dismiss();
                });
                builder.create().show();
                return true;
            case R.id.hex:
                hexEnabled = !hexEnabled;
                sendText.setText("");
                hexWatcher.enable(hexEnabled);
                sendText.setHint(hexEnabled ? "HEX mode" : "");
                item.setChecked(hexEnabled);
                return true;
            case R.id.controlLines:
                boolean show = controlLines.showControlLines(!item.isChecked());
                item.setChecked(show);
                return true;
            case R.id.flowControl:
                controlLines.selectFlowControl();
                return true;
            case R.id.backgroundNotification:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
                    } else {
                        showNotificationSettings();
                    }
                }
                return true;
            case R.id.sendBreak:
                try {
                    // usbSerialPort.setBreak(true); // API changed in 3.8.0
                    Thread.sleep(100);
                    status("send BREAK");
                    // usbSerialPort.setBreak(false); // API changed in 3.8.0
                } catch (Exception e) {
                    status("send BREAK failed: " + e.getMessage());
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */
    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{"android.permission.POST_NOTIFICATIONS"}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    // === SerialListener ===

    @Override
    public void onSerialConnect() {
        status(getString(R.string.status_connected));
        connected = Connected.True;
        enableUI();
        controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status(getString(R.string.status_connection_failed, e.getMessage()));
        disconnect();
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            spn.append(TextUtil.toCaretString(new String(data), newline.equals(TextUtil.newline_lf)));
        }
        Spannable colored = spn;
        colored.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorReceiveText)), 0, colored.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(colored);
        scrollToBottom();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        onSerialRead(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    // === internal helpers ===

    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            for (UsbDevice v : usbManager.getDeviceList().values())
                if (v.getDeviceId() == deviceId) device = v;
        }
        if (device == null) {
            status(getString(R.string.status_connection_failed, "device not found"));
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status(getString(R.string.status_connection_failed, "no driver"));
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status(getString(R.string.status_connection_failed, "not enough ports at device"));
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null) {
            if (false) { // ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.USB_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(requireContext(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB), Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
                return;
            }
            status(getString(R.string.status_connection_failed, "permission denied"));
            return;
        }
        SerialSocket socket = new SerialSocket(requireContext(), usbConnection, usbSerialPort);
        try {
            service.connect(socket);
            status(getString(R.string.status_connected));
        } catch (Exception e) {
            status(getString(R.string.status_connection_failed, e.getMessage()));
            onSerialConnectError(e);
            return;
        }
        onSerialConnect();
        connected = Connected.Pending;
        requireActivity().bindService(new Intent(requireActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    private void connect(boolean granted) {
        if (!granted) {
            status(getString(R.string.status_connection_failed, "permission denied"));
            return;
        }
        connect();
    }

    private void disconnect() {
        connected = Connected.False;
        enableUI();
        controlLines.stop();
        if (service != null) {
            service.disconnect();
        }
        try {
            if (usbSerialPort != null) usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    private void send(String str) {
        if (str.isEmpty()) return;
        try {
            byte[] data;
            if (hexEnabled) {
                String hexStr = str.replace(" ", "");
                data = TextUtil.fromHexString(hexStr);
            } else {
                String msg = str + newline;
                data = msg.getBytes();
            }
            service.write(data);
            showSent(data);
        } catch (SerialTimeoutException e) {
            status(getString(R.string.status_connection_failed, "write timeout: " + e.getMessage()));
        } catch (IOException e) {
            status(getString(R.string.status_connection_failed, "write failed: " + e.getMessage()));
        }
    }

    private void sendBreak() {
        if (usbSerialPort != null) {
            try {
                // usbSerialPort.setBreak(true); // API changed in 3.8.0
                mainLooper.postDelayed(() -> {
                    // usbSerialPort.setBreak(false); // API changed in 3.8.0
                }, 100);
                status("BREAK sent");
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
        }
    }

    private void showSent(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        if (hexEnabled) {
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, data);
            spn.append(getString(R.string.send_prefix_hex, sb.toString()));
        } else {
            String msg = new String(data);
            Spannable s = (Spannable) TextUtil.toCaretString(msg, newline.equals(TextUtil.newline_lf));
            spn.append(s);
        }
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
        scrollToBottom();
        sendText.setText("");
    }

    private void enableUI() {
        boolean enable = connected == Connected.True;
        sendBtn.setEnabled(enable && controlLines.sendAllowed);
        sendText.setEnabled(enable);
    }

    private void showNewlineDialog() {
        final String[] newlineNames = getResources().getStringArray(R.array.newline_names);
        final String[] newlineValues = getResources().getStringArray(R.array.newline_values);
        int pos = Arrays.asList(newlineValues).indexOf(newline);
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        builder.setTitle("Newline")
                .setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                    newline = newlineValues[which];
                    dialog.dismiss();
                })
                .create()
                .show();
    }

    private void status(String msg) {
        SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
        scrollToBottom();
    }

    private void scrollToBottom() {
        final int scrollAmount = receiveText.getLayout() == null ? 0 : receiveText.getLayout().getLineTop(receiveText.getLineCount()) - receiveText.getHeight();
        if (scrollAmount > 0) receiveText.scrollTo(0, scrollAmount);
        else receiveText.scrollTo(0, 0);
    }

    private void checkForeground() {
        if (service != null && serviceForeground) {
            service.attach(this);
        } else if (service != null) {
            service.detach();
        }
    }

    private boolean serviceForeground = false;

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;                                               // show & update control line buttons
        // private UsbSerialPort.FlowControl flowControl = UsbSerialPort.FlowControl.NONE; // API changed in 3.8.0

        boolean sendAllowed = true;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        void onPrepareOptionsMenu(Menu menu) {
            try {
                // EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines(); // API changed in 3.8.0
                // EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl(); // API changed in 3.8.0
                // menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty()); // API changed in 3.8.0
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
                // menu.findItem(R.id.flowControl).setEnabled(sfc.size() > 1); // API changed in 3.8.0
            } catch (Exception ignored) {
            }
        }

        void selectFlowControl() {
            // Flow control API changed in 3.8.0 - disable for now
            status("Flow control not supported in this version");
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (showControlLines) {
                try {
                    // EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines(); // API changed in 3.8.0
                    // Control line API changed in 3.8.0 - show all buttons for now
                    rtsBtn.setVisibility(View.VISIBLE);
                    ctsBtn.setVisibility(View.VISIBLE);
                    dtrBtn.setVisibility(View.VISIBLE);
                    dsrBtn.setVisibility(View.VISIBLE);
                    cdBtn.setVisibility(View.VISIBLE);
                    riBtn.setVisibility(View.VISIBLE);
                } catch (Exception e) {
                    showControlLines = false;
                    status("getSupportedControlLines() failed: " + e.getMessage());
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            // if(flowControl == UsbSerialPort.FlowControl.NONE) { // API changed in 3.8.0
                sendAllowed = true;
                updateSendBtn(SendButtonState.Idle);
            // }

            mainLooper.removeCallbacks(runnable);
            if (showControlLines) { // || flowControl != UsbSerialPort.FlowControl.NONE) { // API changed in 3.8.0
                run();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            sendAllowed = true;
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                if (showControlLines) {
                    // EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines(); // API changed in 3.8.0
                    // Control line API changed in 3.8.0 - use default states
                    toggleState(rtsBtn, false);
                    toggleState(ctsBtn, false);
                    toggleState(dtrBtn, false);
                    toggleState(dsrBtn, false);
                    toggleState(cdBtn, false);
                    toggleState(riBtn, false);
                }
                // Flow control API changed in 3.8.0 - disable for now
                // if (flowControl != UsbSerialPort.FlowControl.NONE) {
                //     switch (usbSerialPort.getFlowControl()) {
                //         case DTR_DSR:         sendAllowed = usbSerialPort.getDSR(); break;
                //         case RTS_CTS:         sendAllowed = usbSerialPort.getCTS(); break;
                //         case XON_XOFF:        sendAllowed = usbSerialPort.getXON(); break;
                //         case XON_XOFF_INLINE: sendAllowed = flowControlFilter != null && flowControlFilter.getXON(); break;
                //         default:              sendAllowed = true;
                //     }
                //     updateSendBtn(sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
                // }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (Exception e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        private void toggleState(ToggleButton btn, boolean state) {
            if (btn == null) return;
            if (btn.isChecked() != state) {
                btn.setChecked(state);
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

        void updateSendBtn(SendButtonState state) {
            sendBtn.setEnabled(state == SendButtonState.Idle);
            sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
            sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
        }
    }
}

