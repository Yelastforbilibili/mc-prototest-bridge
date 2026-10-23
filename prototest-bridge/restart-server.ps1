# Restart a local Paper test server and wait until the bridge answers.
#
# Convenience for a local rig: it kills a running server, removes the stale world lock, launches the
# server detached and polls the bridge. It assumes this script sits next to the plugin sources and that
# the server directory contains either `start-server.bat` or a runnable Paper jar.
#
# Usage:  pwsh -File restart-server.ps1 -ServerDir 'C:\path\to\paper-server'
param(
    [string]$ServerDir = $env:MC_SERVER_DIR,
    [string]$BridgeUrl = 'http://127.0.0.1:25963',
    [int]$WaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
if (-not $ServerDir -or -not (Test-Path $ServerDir)) {
    throw "Pass -ServerDir 'C:\path\to\paper-server' (or set MC_SERVER_DIR)."
}

# Stop the running server and nothing else. `start /b` children are not tracked by the calling shell, and a
# second server would fail on the port plus the world lock.
#
# Never touch every java.exe: a Minecraft client is a java.exe too, and players run one next to this rig. The
# listener on the server port identifies the server exactly; the command-line match is only a fallback for the
# case where the server is still booting and has not bound the port yet.
$serverPort = 25565
$properties = Join-Path $ServerDir 'server.properties'
if (Test-Path $properties) {
    $portLine = Select-String -Path $properties -Pattern '^server-port=' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portLine) { $serverPort = [int]$portLine.Line.Split('=')[1] }
}
$pids = @()
try {
    $pids = @(Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess)
} catch {
    $pids = @()
}
if (-not $pids -or $pids.Count -eq 0) {
    $pids = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and ($_.CommandLine.Contains($ServerDir) -or $_.CommandLine -match 'paper-[0-9.]+-\d+\.jar') } |
        Select-Object -ExpandProperty ProcessId)
}
foreach ($pid_ in ($pids | Select-Object -Unique)) {
    Write-Output "stopping server pid $pid_ (port $serverPort)"
    Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
}

# Wait for the port to be free before removing the log the old process still holds.
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $busy = Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction SilentlyContinue
    if (-not $busy) { break }
}
Start-Sleep -Seconds 2

Remove-Item (Join-Path $ServerDir 'world\session.lock') -Force -ErrorAction SilentlyContinue
$log = Join-Path $ServerDir 'run-stdout.log'
for ($i = 0; $i -lt 10; $i++) {
    try {
        Remove-Item $log -Force -ErrorAction Stop
        break
    } catch {
        Start-Sleep -Seconds 1
    }
}

$bat = Join-Path $ServerDir 'start-server.bat'
if (Test-Path $bat) {
    cmd /c "start `"prototest-server`" /b `"$bat`""
} else {
    $paper = Get-ChildItem $ServerDir -Filter 'paper-*.jar' | Sort-Object Length -Descending | Select-Object -First 1
    if (-not $paper) { throw "no start-server.bat and no paper-*.jar in $ServerDir" }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    # -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT: Paper first-start downloads the vanilla jar, and the JDK
    # trust store does not know about a TLS-intercepting proxy.
    $jvmArgs = @('-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT', '-Xms1G', '-Xmx3G', '-jar', $paper.FullName, '--nogui')
    cmd /c "start `"prototest-server`" /b `"$java`" $($jvmArgs -join ' ') > `"$log`" 2>&1"
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$status = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    try {
        $status = Invoke-RestMethod -Uri "$BridgeUrl/status" -TimeoutSec 2
        if ($status.ok) { break }
    } catch {
        $status = $null   # bridge not listening yet
    }
}
if (-not $status) {
    Write-Output '--- last log lines ---'
    Get-Content $log -Tail 20 -ErrorAction SilentlyContinue
    throw "bridge did not answer within $WaitSeconds s"
}

Write-Output ("server up: {0} | tick {1} | {2} worlds | bridge {3}" -f $status.serverVersion, $status.tick, $status.worlds.Count, $BridgeUrl)
