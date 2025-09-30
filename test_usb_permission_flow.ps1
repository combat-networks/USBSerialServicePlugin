# 🔧 USBSerialManager单例架构修复 - 快速测试脚本
# 用于验证USBSerialPermissionReceiver能否正确访问USBSerialManager实例

Write-Host "🚀 开始USBSerialManager单例架构修复测试..." -ForegroundColor Green

# 检查APK文件是否存在
$apkPath = "app\build\outputs\apk\civ\debug\SAE-Plugin-USBSerial-1.0-686b294c-1.6.0-civ-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "❌ APK文件不存在: $apkPath" -ForegroundColor Red
    Write-Host "请先运行: ./gradlew assembleCivDebug" -ForegroundColor Yellow
    exit 1
}

Write-Host "✅ 找到APK文件: $apkPath" -ForegroundColor Green

# 检查ADB连接
Write-Host "🔍 检查ADB设备连接..." -ForegroundColor Cyan
$devices = adb devices
if ($devices -match "device$") {
    Write-Host "✅ 检测到ADB设备连接" -ForegroundColor Green
}
else {
    Write-Host "❌ 未检测到ADB设备连接" -ForegroundColor Red
    Write-Host "请确保设备已连接并启用USB调试" -ForegroundColor Yellow
    exit 1
}

# 安装APK
Write-Host "📱 安装APK到设备..." -ForegroundColor Cyan
$installResult = adb install -r $apkPath
if ($installResult -match "Success") {
    Write-Host "✅ APK安装成功" -ForegroundColor Green
}
else {
    Write-Host "❌ APK安装失败" -ForegroundColor Red
    Write-Host $installResult -ForegroundColor Yellow
    exit 1
}

# 启动日志监控
Write-Host "📋 启动日志监控..." -ForegroundColor Cyan
Write-Host "监控标签: USBSerialManager, USBSerialLifecycle, USBSerialPermissionReceiver, USBSerialDropDownReceiver" -ForegroundColor Yellow
Write-Host "按 Ctrl+C 停止日志监控" -ForegroundColor Yellow
Write-Host ""

# 启动日志监控命令
$logCommand = "adb logcat -s USBSerialManager USBSerialLifecycle USBSerialPermissionReceiver USBSerialDropDownReceiver"

Write-Host "🔍 执行命令: $logCommand" -ForegroundColor Cyan
Write-Host ""

# 执行日志监控
try {
    Invoke-Expression $logCommand
}
catch {
    Write-Host "❌ 日志监控启动失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "📋 测试步骤提醒:" -ForegroundColor Green
Write-Host "1. 插入USB串口设备" -ForegroundColor White
Write-Host "2. 观察权限请求对话框" -ForegroundColor White
Write-Host "3. 授予或拒绝权限" -ForegroundColor White
Write-Host "4. 检查日志输出中的关键信息:" -ForegroundColor White
Write-Host "   - USBSerialLifecycle: 📝 Registering USBSerialManager instance" -ForegroundColor Yellow
Write-Host "   - USBSerialPermissionReceiver: ✅ Found USBSerialManager instance" -ForegroundColor Yellow
Write-Host "   - USBSerialManager: ✅ USB permission granted" -ForegroundColor Yellow
Write-Host ""
Write-Host "🎯 关键验证点:" -ForegroundColor Green
Write-Host "✅ 实例注册成功" -ForegroundColor White
Write-Host "✅ 实例获取成功" -ForegroundColor White
Write-Host "✅ 权限处理成功" -ForegroundColor White
Write-Host "✅ 设备连接成功" -ForegroundColor White

