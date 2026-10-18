# Build the prototest-bridge plugin without Gradle.
#
# Why not Gradle: the wrapper cannot fetch its distribution through a TLS-intercepting proxy
# (PKIX path building failed) on some machines, and the plugin needs the *server's* jars on the compile
# classpath anyway (it reads net.minecraft internals that paper-api does not expose).
#
# Why not javac: javac 25.0.3 crashes with an internal NPE
#   (Flow$AssignAnalyzer.visitIdent: Cannot read field "kind" because "tree.sym" is null)
# on this source set. ECJ 3.42 (Eclipse compiler, fetched into libs/ by fetch-ecj.ps1) compiles the
# same sources against the same Java 25 classpath without complaint. ECJ maxes out at source level
# 24, which is fine: the plugin only has to *read* the server's Java 25 classes.
#
# Usage:
#   pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' [-Deploy]
#   $env:MC_SERVER_DIR may hold the server path instead of -ServerDir.
param(
    [switch]$Deploy,
    [string]$ServerDir = $env:MC_SERVER_DIR,
    [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'
$proj = $PSScriptRoot

# `java` is often on PATH through a shim directory that does not contain `jar`, so locate a real JDK
# instead of trusting PATH alone.
function Find-JdkHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $roots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
        '/usr/lib/jvm',
        '/Library/Java/JavaVirtualMachines'
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $hit = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

$jdk = Find-JdkHome
$java = if ($jdk) { Join-Path $jdk 'bin\java.exe' } else { (Get-Command java).Source }
$jarTool = if ($jdk) { Join-Path $jdk 'bin\jar.exe' } else { $null }
if (-not $jarTool -or -not (Test-Path $jarTool)) {
    $cmd = Get-Command jar -ErrorAction SilentlyContinue
    $jarTool = if ($cmd) { $cmd.Source } else { $null }
}

$ecj = Join-Path $proj 'libs\ecj-3.42.0.jar'
if (-not (Test-Path $ecj)) {
    & (Join-Path $proj 'fetch-ecj.ps1') -Version '3.42.0'
}

if (-not $ServerDir) {
    throw "No server directory. Pass -ServerDir 'C:\path\to\paper-server' (or set MC_SERVER_DIR): the plugin " +
          "compiles against the server's own jars, because paper-api does not expose the block state registry."
}
if (-not (Test-Path $ServerDir)) { throw "server directory not found: $ServerDir" }

# The version jar carries NMS + CraftBukkit; the unpacked libraries carry everything else.
$versionJars = @(Get-ChildItem (Join-Path $ServerDir 'versions') -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending)
if ($versionJars.Count -eq 0) { throw "no server jar found under $ServerDir\versions" }
$libraries = @(Get-ChildItem (Join-Path $ServerDir 'libraries') -Recurse -Filter *.jar -ErrorAction SilentlyContinue)
if ($libraries.Count -eq 0) { throw "no libraries found under $ServerDir\libraries (start the server once to unpack them)" }
$cp = (($libraries.FullName + $versionJars[0].FullName) -join ';')

$classes = Join-Path $proj 'build\classes'
$resources = Join-Path $proj 'build\resources'
Remove-Item -Recurse -Force $classes, $resources -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $resources | Out-Null

$sources = @(Get-ChildItem (Join-Path $proj 'src\main\java') -Recurse -Filter *.java).FullName
if ($sources.Count -eq 0) { throw "no java sources under src\main\java" }
& $java -jar $ecj -24 -nowarn -encoding UTF-8 -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "ecj failed with $LASTEXITCODE" }

Copy-Item (Join-Path $proj 'src\main\resources\*') $resources -Recurse -Force
$pluginYml = [IO.File]::ReadAllText((Join-Path $resources 'plugin.yml')) -replace '\$\{version\}', $Version
[IO.File]::WriteAllText((Join-Path $resources 'plugin.yml'), $pluginYml, (New-Object System.Text.UTF8Encoding($false)))

$jarPath = Join-Path $proj "build\libs\prototest-bridge-$Version.jar"
New-Item -ItemType Directory -Force -Path (Split-Path $jarPath) | Out-Null
Remove-Item $jarPath -ErrorAction SilentlyContinue

if ($jarTool -and (Test-Path $jarTool)) {
    & $jarTool --create --file $jarPath -C $classes . -C $resources .
    if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }
} else {
    # No JDK `jar` available (common when java comes from a PATH shim): zip the two trees instead.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stage = Join-Path $proj 'build\jar-stage'
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item (Join-Path $classes '*') $stage -Recurse -Force
    Copy-Item (Join-Path $resources '*') $stage -Recurse -Force
    [System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $jarPath)
}

Write-Output "built $jarPath ($((Get-Item $jarPath).Length) bytes)"

if ($Deploy) {
    $plugins = Join-Path $ServerDir 'plugins'
    New-Item -ItemType Directory -Force -Path $plugins | Out-Null
    Copy-Item $jarPath $plugins -Force
    Write-Output "deployed to $plugins"
    Write-Output 'restart the server to load it'
}
