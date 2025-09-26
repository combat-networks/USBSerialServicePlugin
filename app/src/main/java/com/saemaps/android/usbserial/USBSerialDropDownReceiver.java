package com.saemaps.android.usbserial;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.saemaps.android.maps.MapView;
import com.saemaps.android.usbserial.plugin.R;
import com.saemaps.android.usbserial.usbserial.USBSerialService;
import com.saemaps.android.dropdown.DropDown.OnStateListener;
import com.saemaps.android.dropdown.DropDownReceiver;

import com.saemaps.android.usbserial.PlatformProxy;
import com.saemaps.coremap.log.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class USBSerialDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    public static final String TAG = "USBSerial";

    public static final String SHOW_PLUGIN = "com.saemaps.android.usbserial.SHOW_PLUGIN";
    private final View templateView;
    private final Context pluginContext;
    
    // USB串口相关
    private USBSerialService serialService;
    private boolean serviceBound = false;
    private List<UsbDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private UsbDevice selectedDevice;
    
    // UI组件
    private TextView tvDeviceStatus;
    private Button btnScan;
    private Button btnConnect;
    private ListView lvDevices;
    private EditText etBaudrate;
    private EditText etDatabits;
    private EditText etSendData;
    private Button btnSend;
    private TextView tvReceivedData;
    private Button btnClear;

    /**************************** CONSTRUCTOR *****************************/

    public USBSerialDropDownReceiver(final MapView mapView,
                                          final Context context) {
        super(mapView);
        this.pluginContext = context;

        // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
        // In this case, using it is not necessary - but I am putting it here to remind
        // developers to look at this Inflator
        templateView = PluginLayoutInflater.inflate(context,
                R.layout.main_layout, null);
        
        // 初始化UI组件
        initViews();
        
        // 初始化设备列表适配器
        deviceAdapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1);
        lvDevices.setAdapter(deviceAdapter);
        
        // 绑定USB串口服务
        bindSerialService();
        
        // 注册数据接收广播
        registerDataReceiver();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvDeviceStatus = templateView.findViewById(R.id.tv_device_status);
        btnScan = templateView.findViewById(R.id.btn_scan);
        btnConnect = templateView.findViewById(R.id.btn_connect);
        lvDevices = templateView.findViewById(R.id.lv_devices);
        etBaudrate = templateView.findViewById(R.id.et_baudrate);
        etDatabits = templateView.findViewById(R.id.et_databits);
        etSendData = templateView.findViewById(R.id.et_send_data);
        btnSend = templateView.findViewById(R.id.btn_send);
        tvReceivedData = templateView.findViewById(R.id.tv_received_data);
        btnClear = templateView.findViewById(R.id.btn_clear);
        
        // 设置按钮点击事件
        btnScan.setOnClickListener(v -> scanDevices());
        btnConnect.setOnClickListener(v -> connectToSelectedDevice());
        btnSend.setOnClickListener(v -> sendData());
        btnClear.setOnClickListener(v -> clearReceivedData());
        
        // 设置设备列表点击事件
        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            selectedDevice = deviceList.get(position);
            btnConnect.setEnabled(true);
            Log.d(TAG, "选择设备: " + selectedDevice.getDeviceName());
        });
    }
    
    /**
     * 绑定USB串口服务
     */
    private void bindSerialService() {
        Intent intent = new Intent(pluginContext, USBSerialService.class);
        pluginContext.startService(intent);
        pluginContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    /**
     * 服务连接回调
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            USBSerialService.USBSerialBinder binder = (USBSerialService.USBSerialBinder) service;
            serialService = binder.getService();
            serviceBound = true;
            Log.d(TAG, "USB串口服务已连接");
            updateDeviceStatus();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serialService = null;
            serviceBound = false;
            Log.d(TAG, "USB串口服务已断开");
        }
    };
    
    /**
     * 注册数据接收广播
     */
    private void registerDataReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.saemaps.android.usbserial.DATA_RECEIVED");
        pluginContext.registerReceiver(dataReceiver, filter);
    }
    
    /**
     * 数据接收广播接收器
     */
    private final BroadcastReceiver dataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.saemaps.android.usbserial.DATA_RECEIVED".equals(intent.getAction())) {
                byte[] data = intent.getByteArrayExtra("data");
                if (data != null) {
                    appendReceivedData(data);
                }
            }
        }
    };

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
        // 解绑服务
        if (serviceBound) {
            pluginContext.unbindService(serviceConnection);
            serviceBound = false;
        }
        
        // 注销广播接收器
        try {
            pluginContext.unregisterReceiver(dataReceiver);
        } catch (Exception e) {
            Log.e(TAG, "注销数据接收器失败", e);
        }
    }
    
    /**
     * 扫描USB设备
     */
    private void scanDevices() {
        if (serialService != null) {
            serialService.scanDevices();
            Toast.makeText(pluginContext, "正在扫描USB设备...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(pluginContext, "USB串口服务未连接", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 连接到选中的设备
     */
    private void connectToSelectedDevice() {
        if (selectedDevice == null) {
            Toast.makeText(pluginContext, "请先选择设备", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (serialService != null) {
            // 设置串口参数
            try {
                int baudRate = Integer.parseInt(etBaudrate.getText().toString());
                int dataBits = Integer.parseInt(etDatabits.getText().toString());
                serialService.setSerialParameters(baudRate, dataBits, 1, 0);
            } catch (NumberFormatException e) {
                Toast.makeText(pluginContext, "串口参数格式错误", Toast.LENGTH_SHORT).show();
                return;
            }
            
            serialService.connectToDevice(selectedDevice);
            Toast.makeText(pluginContext, "正在连接设备...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(pluginContext, "USB串口服务未连接", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 发送数据
     */
    private void sendData() {
        String data = etSendData.getText().toString();
        if (data.isEmpty()) {
            Toast.makeText(pluginContext, "请输入要发送的数据", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (serialService != null && serialService.isConnected()) {
            try {
                serialService.sendString(data + "\n"); // 添加换行符
                etSendData.setText(""); // 清空输入框
                Toast.makeText(pluginContext, "数据已发送", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(pluginContext, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(pluginContext, "设备未连接", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 清空接收数据
     */
    private void clearReceivedData() {
        tvReceivedData.setText("等待数据...");
    }
    
    /**
     * 添加接收到的数据
     */
    private void appendReceivedData(byte[] data) {
        String text = new String(data);
        String currentText = tvReceivedData.getText().toString();
        if ("等待数据...".equals(currentText)) {
            tvReceivedData.setText(text);
        } else {
            tvReceivedData.append(text);
        }
    }
    
    /**
     * 更新设备状态显示
     */
    private void updateDeviceStatus() {
        if (serialService != null) {
            if (serialService.isConnected()) {
                tvDeviceStatus.setText("设备状态: 已连接 - " + serialService.getCurrentDeviceInfo());
                btnSend.setEnabled(true);
                btnConnect.setText("断开");
            } else {
                tvDeviceStatus.setText("设备状态: 未连接");
                btnSend.setEnabled(false);
                btnConnect.setText("连接");
            }
        } else {
            tvDeviceStatus.setText("设备状态: 服务未连接");
            btnSend.setEnabled(false);
            btnConnect.setEnabled(false);
        }
    }
    
    /**
     * 更新设备列表
     */
    private void updateDeviceList(List<UsbDevice> devices) {
        deviceList.clear();
        deviceList.addAll(devices);
        
        deviceAdapter.clear();
        for (UsbDevice device : devices) {
            String deviceInfo = device.getDeviceName() + " (VID:" + device.getVendorId() + 
                              ", PID:" + device.getProductId() + ")";
            deviceAdapter.add(deviceInfo);
        }
        deviceAdapter.notifyDataSetChanged();
        
        if (devices.isEmpty()) {
            Toast.makeText(pluginContext, "未找到USB串口设备", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(pluginContext, "找到 " + devices.size() + " 个设备", Toast.LENGTH_SHORT).show();
        }
    }

    /**************************** INHERITED METHODS *****************************/

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "USBSerialDropDownReceiver onReceive called - DEBUG");
        System.out.println("USBSerialDropDownReceiver onReceive called - SYSTEM OUT");

        final String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "Received intent with null action - DEBUG");
            System.out.println("USBSerialDropDownReceiver received intent with null action - SYSTEM OUT");
            return;
        }

        Log.d(TAG, "Received action: " + action + " - DEBUG");
        System.out.println("USBSerialDropDownReceiver received action: " + action + " - SYSTEM OUT");

        if (action.equals(SHOW_PLUGIN)) {
            Log.d(TAG, "showing plugin drop down - DEBUG");
            System.out.println("USBSerialDropDownReceiver showing plugin drop down - SYSTEM OUT");
            
            // 显示UI界面
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);
            
            // 更新设备状态
            updateDeviceStatus();
            
            // 自动扫描设备
            scanDevices();
            
            // PlatformProxy逻辑（保留原有功能）
            new Thread(()->{
                PlatformProxy proxy = PlatformProxy.getInstance();
                if (proxy == null) {
                    Log.w(TAG, "平台代理未初始化");
                    return;
                }
                
                while (!proxy.isInit()){
                    try {
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "平台代理已初始化");
            }).start();
        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        // 插件关闭
    }
}