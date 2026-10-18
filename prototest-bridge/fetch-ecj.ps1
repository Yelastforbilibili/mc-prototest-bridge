# Downloads the Eclipse compiler (ECJ) used by build.ps1.
#
# build.ps1 compiles with ECJ instead of javac, because javac 25 crashes on this source set:
#   An exception has occurred in the compiler (25.0.3)
#   java.lang.NullPointerException: Cannot read field "kind" because "tree.sym" is null
#     at com.sun.tools.javac.comp.Flow$AssignAnalyzer.visitIdent(Flow.java:3189)
# ECJ only has to *read* Java 25 class files, so its own source level (24) is irrelevant here.
#
# Usage:  pwsh -File fetch-ecj.ps1 [-Force]
param(
    [switch]$Force,
    [string]$Version = '3.42.0'
)

$ErrorActionPreference = 'Stop'
$libs = Join-Path $PSScriptRoot 'libs'
$target = Join-Path $libs "ecj-$Version.jar"
$url = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$Version/ecj-$Version.jar"

if ((Test-Path $target) -and -not $Force) {
    Write-Output "ecj already present: $target"
    return
}

New-Item -ItemType Directory -Force -Path $libs | Out-Null
Write-Output "downloading $url"
# Invoke-WebRequest goes through the system proxy, which a bare `java` download would not.
Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing
$size = (Get-Item $target).Length
if ($size -lt 1000000) { throw "downloaded file looks too small ($size bytes): $target" }
Write-Output "saved $target ($size bytes)"
