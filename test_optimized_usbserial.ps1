# USB串口插件优化版本测试脚本
# 测试优化后的USBSerialManager的稳定性和性能

Write-Host "🚀 开始测试优化后的USB串口插件..." -ForegroundColor Green

# 检查APK文件是否存在
$apkPath = "app\build\outputs\apk\civ\debug\SAE-Plugin-USBSerial-1.0-c2aa94af-1.6.0-civ-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "✅ APK文件存在: $apkPath" -ForegroundColor Green
}
else {
    Write-Host "❌ APK文件不存在: $apkPath" -ForegroundColor Red
    exit 1
}

# 检查设备连接
Write-Host "📱 检查设备连接..." -ForegroundColor Yellow
$devices = adb devices
Write-Host $devices

if ($devices -match "device$") {
    Write-Host "✅ 设备已连接" -ForegroundColor Green
}
else {
    Write-Host "❌ 没有设备连接，请连接Android设备" -ForegroundColor Red
    exit 1
}

# 安装APK
Write-Host "📦 安装优化后的USB串口插件..." -ForegroundColor Yellow
adb install -r $apkPath

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ APK安装成功" -ForegroundColor Green
}
else {
    Write-Host "❌ APK安装失败" -ForegroundColor Red
    exit 1
}

# 启动logcat监控
Write-Host "📊 启动logcat监控..." -ForegroundColor Yellow
Write-Host "监控关键词: USBSerial, USB, Serial, Error, Exception" -ForegroundColor Cyan

# 启动logcat并过滤相关日志
$logcatProcess = Start-Process -FilePath "adb" -ArgumentList "logcat", "-s", "USBSerialManager:V", "USBSerialTestReceiver:V", "USBSerialLifecycle:V", "USBSerialMapComponent:V", "USBSerialService:V", "AndroidRuntime:E" -PassThru -NoNewWindow

# 等待logcat启动
Start-Sleep -Seconds 2

Write-Host "🎯 测试步骤:" -ForegroundColor Cyan
Write-Host "1. 在主程序中打开USB串口插件" -ForegroundColor White
Write-Host "2. 插入USB串口设备" -ForegroundColor White
Write-Host "3. 观察日志输出，验证优化效果" -ForegroundColor White
Write-Host "4. 测试数据传输功能" -ForegroundColor White
Write-Host ""
Write-Host "🔍 关键优化验证点:" -ForegroundColor Cyan
Write-Host "✅ CH340状态错误是否被忽略" -ForegroundColor Green
Write-Host "✅ 数据处理是否使用缓冲机制" -ForegroundColor Green
Write-Host "✅ 重连策略是否智能" -ForegroundColor Green
Write-Host "✅ 是否移除了冗余的状态监控" -ForegroundColor Green
Write-Host ""
Write-Host "按 Ctrl+C 停止监控" -ForegroundColor Yellow

# 等待用户中断
try {
    while ($true) {
        Start-Sleep -Seconds 1
    }
}
catch {
    Write-Host "`n🛑 停止监控" -ForegroundColor Yellow
}

# 清理进程
if ($logcatProcess -and !$logcatProcess.HasExited) {
    $logcatProcess.Kill()
    Write-Host "✅ logcat进程已停止" -ForegroundColor Green
}

Write-Host "🎉 测试完成！" -ForegroundColor Green
