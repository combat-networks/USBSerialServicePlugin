# USB权限修复测试指南

## 修复内容总结

### 1. USBSerialManager.java 修复
- ✅ 添加了详细的USB权限检查日志
- ✅ 增强了权限请求方法的错误处理
- ✅ 改进了广播接收器中的权限处理逻辑
- ✅ 添加了异常捕获和错误报告

### 2. USBSerialDropDownReceiver.java 修复
- ✅ 添加了USB权限广播接收器
- ✅ 实现了权限授予后的自动连接逻辑
- ✅ 添加了详细的日志记录

## 测试步骤

### 1. 安装应用
```bash
adb install -r app/build/outputs/apk/civ/debug/app-civ-debug.apk
```

### 2. 连接USB设备
- 连接USB串口设备到Android设备
- 观察logcat输出，应该看到：
  ```
  D/USBSerialManager: USB device attached: [设备信息]
  D/USBSerialManager: No permission for device: [设备信息], requesting permission...
  D/USBSerialManager: Requesting USB permission for device: [设备信息]
  D/USBSerialManager: Permission request sent successfully
  ```

### 3. 授予权限
- 当系统弹出USB权限对话框时，点击"确定"
- 观察logcat输出，应该看到：
  ```
  D/USBSerialManager: USB permission result received for device: [设备信息], granted: true
  D/USBSerialManager: USB permission granted, attempting to connect: [设备信息]
  D/USBSerialManager: Permission already granted for device: [设备信息]
  ```

### 4. 验证连接
- 检查是否成功连接到USB设备
- 观察logcat中是否有连接成功的日志

## 预期结果

修复后，USB权限处理应该更加稳定：
1. 权限请求不会导致应用崩溃
2. 权限授予后能自动连接设备
3. 有详细的日志记录便于调试
4. 错误处理更加完善

## 日志关键词

在logcat中搜索以下关键词来监控USB权限处理：
- `USBSerialManager`
- `USB permission`
- `Permission granted`
- `Permission denied`
- `USB device attached`
- `USB device detached`
