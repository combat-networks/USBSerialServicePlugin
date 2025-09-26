# USB串口服务插件使用说明

## 📋 插件概述

USB串口服务插件是一个基于SAE平台的USB串口通信插件，支持多种USB转串口芯片，提供完整的串口通信功能。

## 🚀 功能特性

### 支持的USB设备
- **FTDI系列**: FT232, FT2232, FT232H等
- **Prolific**: PL2303
- **Silabs**: CP2102, CP2105, CP2108等
- **Qinheng**: CH340, CH341
- **Arduino设备**: Leonardo, Micro等
- **BBC micro:bit**
- **通用CDC设备**

### 主要功能
- ✅ USB设备自动检测和枚举
- ✅ 设备权限自动请求和管理
- ✅ 串口参数配置（波特率、数据位、停止位、校验位）
- ✅ 实时数据发送和接收
- ✅ 后台服务支持
- ✅ 设备连接状态监控
- ✅ 多设备支持

## 🛠️ 安装和配置

### 1. 插件安装
1. 将插件APK文件安装到SAE设备
2. 在SAE工具菜单中找到"USBSerial Plugin"
3. 点击启动插件

### 2. 权限配置
插件会自动请求USB权限，首次使用时需要用户授权。

### 3. 设备连接
1. 将USB串口设备连接到Android设备
2. 点击"扫描设备"按钮
3. 从设备列表中选择目标设备
4. 配置串口参数
5. 点击"连接"按钮

## 📱 界面说明

### 主界面组件

#### 设备状态区域
- **设备状态**: 显示当前连接状态和设备信息
- **扫描设备**: 扫描可用的USB串口设备
- **连接/断开**: 连接或断开选中的设备

#### 设备列表
- 显示所有检测到的USB串口设备
- 包含设备名称、VID、PID等信息
- 点击选择要连接的设备

#### 串口参数设置
- **波特率**: 设置通信波特率（默认115200）
- **数据位**: 设置数据位数（默认8位）
- **停止位**: 固定为1位
- **校验位**: 固定为无校验

#### 数据通信区域
- **发送数据**: 输入要发送的数据
- **发送按钮**: 发送数据到串口
- **接收数据**: 显示接收到的数据
- **清空按钮**: 清空接收数据显示

## 🔧 使用方法

### 基本使用流程

1. **启动插件**
   ```
   SAE工具菜单 → USBSerial Plugin
   ```

2. **连接设备**
   ```
   扫描设备 → 选择设备 → 设置参数 → 连接
   ```

3. **发送数据**
   ```
   在发送框中输入数据 → 点击发送按钮
   ```

4. **接收数据**
   ```
   接收到的数据会自动显示在接收区域
   ```

### 高级功能

#### 串口参数配置
```java
// 支持的波特率
9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600

// 支持的数据位
5, 6, 7, 8

// 支持的停止位
1, 2

// 支持的校验位
0=无校验, 1=奇校验, 2=偶校验
```

#### 数据格式
- **文本数据**: 直接输入文本，自动添加换行符
- **二进制数据**: 支持十六进制格式输入
- **控制字符**: 支持\n, \r, \t等转义字符

## 🐛 故障排除

### 常见问题

#### 1. 设备未检测到
**可能原因**:
- USB设备未正确连接
- 设备驱动不支持
- USB权限未授予

**解决方法**:
- 检查USB连接
- 确认设备在支持列表中
- 重新授权USB权限

#### 2. 连接失败
**可能原因**:
- 设备已被其他应用占用
- 串口参数不匹配
- 设备权限问题

**解决方法**:
- 关闭其他串口应用
- 检查串口参数设置
- 重新请求设备权限

#### 3. 数据发送失败
**可能原因**:
- 设备未连接
- 串口参数错误
- 设备响应超时

**解决方法**:
- 确认设备连接状态
- 检查串口参数
- 降低发送频率

#### 4. 接收不到数据
**可能原因**:
- 设备未发送数据
- 串口参数不匹配
- 数据格式问题

**解决方法**:
- 确认设备正在发送数据
- 检查串口参数设置
- 检查数据格式

### 调试信息

插件提供详细的调试日志，可以通过以下方式查看：

```bash
# 查看插件日志
adb logcat | grep "USBSerial"

# 查看USB相关日志
adb logcat | grep "USB"

# 查看串口相关日志
adb logcat | grep "Serial"
```

## 📚 开发接口

### 主要类说明

#### USBSerialManager
USB串口管理器，负责设备检测、连接、数据通信。

```java
// 创建管理器
USBSerialManager manager = new USBSerialManager(context);

// 设置监听器
manager.setListener(listener);

// 扫描设备
manager.scanDevices();

// 连接设备
manager.connectToDevice(device);

// 发送数据
manager.sendString("Hello World");

// 设置串口参数
manager.setSerialParameters(115200, 8, 1, 0);
```

#### USBSerialService
USB串口后台服务，管理串口连接和数据缓冲。

```java
// 启动服务
Intent intent = new Intent(context, USBSerialService.class);
context.startService(intent);

// 绑定服务
context.bindService(intent, connection, Context.BIND_AUTO_CREATE);

// 发送数据
service.sendString("Data to send");

// 获取连接状态
boolean connected = service.isConnected();
```

### 事件监听

```java
public interface USBSerialListener {
    void onDeviceDetected(List<UsbDevice> devices);
    void onDeviceConnected(UsbDevice device);
    void onDeviceDisconnected();
    void onDataReceived(byte[] data);
    void onError(Exception error);
    void onPermissionDenied(UsbDevice device);
}
```

## 🔒 安全注意事项

1. **权限管理**: 插件需要USB权限，请谨慎授权
2. **数据安全**: 串口通信数据未加密，请注意数据安全
3. **设备保护**: 避免在设备连接时强制拔出USB线
4. **资源管理**: 使用完毕后及时断开连接释放资源

## 📞 技术支持

如遇到问题，请提供以下信息：
- 设备型号和Android版本
- USB设备型号和VID/PID
- 错误日志和截图
- 复现步骤

## 📄 许可证

本插件基于开源许可证发布，具体条款请参考项目根目录的LICENSE文件。

---

**版本**: 1.0  
**兼容性**: SAE 1.6.0+  
**最后更新**: 2024年
