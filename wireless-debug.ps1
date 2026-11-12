param(
    [string]$IpPort = "192.168.1.7:5555",
    [string]$PairIpPort = "",
    [string]$PairCode = "",
    [switch]$InstallApk
)

$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\openjdk17\current"
$env:ANDROID_HOME = "$env:USERPROFILE\scoop\apps\android-clt\current"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "=== REACH Wireless Debugging Helper ===" -ForegroundColor Cyan

# 1. If a pairing IP:Port and 6-digit code were provided, pair first
if ($PairIpPort -ne "" -and $PairCode -ne "") {
    Write-Host "Pairing with $PairIpPort..." -ForegroundColor Yellow
    adb pair $PairIpPort $PairCode
}

# 2. If a direct IP:Port was provided, connect to it and also pin port 5555 so MIUI doesn't close the random port
if ($IpPort -ne "") {
    if ($IpPort -notmatch ":") { $IpPort = "${IpPort}:5555" }
    Write-Host "Connecting to $IpPort..." -ForegroundColor Yellow
    adb connect $IpPort
    if ($IpPort -notmatch ":5555$" -and ((adb devices) -match [regex]::Escape($IpPort))) {
        $ipOnly = $IpPort.Split(":")[0]
        Write-Host "Pinning persistent ADB TCP/IP port 5555 on $ipOnly..." -ForegroundColor Green
        adb -s $IpPort tcpip 5555
        Start-Sleep -Seconds 2
        adb connect "${ipOnly}:5555"
    }
}

# 3. Check if a USB device is currently plugged in; if so, automatically switch it to TCP/IP 5555 and connect over Wi-Fi!
$usbLines = (adb devices -l) | Where-Object { $_ -match "\sdevice\s" -and $_ -notmatch ":" }
if ($usbLines) {
    Write-Host "USB device detected! Enabling TCP/IP port 5555 for wireless debugging..." -ForegroundColor Green
    $wlanIp = (adb shell ip -f inet addr show wlan0 | Select-String -Pattern "inet (\d+\.\d+\.\d+\.\d+)").Matches.Groups[1].Value
    adb tcpip 5555
    Start-Sleep -Seconds 2
    if ($wlanIp) {
        Write-Host "Phone Wi-Fi IP is $wlanIp. Connecting wirelessly to ${wlanIp}:5555..." -ForegroundColor Green
        adb connect "${wlanIp}:5555"
    }
}

# 4. Check mDNS auto-discovery (Android 11+ Wireless Debugging on same Wi-Fi)
$mdns = (adb mdns services) | Where-Object { $_ -match "_adb-tls-connect\._tcp\s+(\d+\.\d+\.\d+\.\d+:\d+)" }
if ($mdns) {
    foreach ($line in $mdns) {
        if ($line -match "(\d+\.\d+\.\d+\.\d+:\d+)") {
            $endpoint = $Matches[1]
            Write-Host "Found Android Wireless Debugging service via mDNS at $endpoint -- connecting..." -ForegroundColor Green
            adb connect $endpoint
        }
    }
}

Write-Host "`nActive ADB Devices:" -ForegroundColor Cyan
adb devices -l

if ($InstallApk) {
    $apk = Join-Path $PSScriptRoot "android\app\build\outputs\apk\debug\app-debug.apk"
    $onlineLine = (adb devices) | Where-Object { $_ -match "^(\S+)\s+device$" } | Select-Object -First 1
    if ($onlineLine -match "^(\S+)\s+device$") {
        $targetDev = $Matches[1]
        if (Test-Path $apk) {
            Write-Host "`nStaging $apk onto $targetDev..." -ForegroundColor Cyan
            adb -s $targetDev push $apk /data/local/tmp/reach-update.apk
            adb -s $targetDev shell input keyevent KEYCODE_WAKEUP
            Write-Host "Triggering install prompt on phone screen -- tap 'Install' now!" -ForegroundColor Yellow
            adb -s $targetDev shell pm install -r /data/local/tmp/reach-update.apk
            if ($LASTEXITCODE -eq 0) {
                adb -s $targetDev shell am force-stop com.reach.app
                adb -s $targetDev shell am start -n com.reach.app/.MainActivity
            } else {
                Write-Host "Install did not complete (check phone screen for 'Install via USB' prompt)." -ForegroundColor Red
                exit $LASTEXITCODE
            }
        }
    } else {
        Write-Host "No online ADB device found to install APK." -ForegroundColor Red
    }
}
