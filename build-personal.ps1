param(
    [ValidateSet('Build', 'Install')]
    [string]$Action = 'Build',
    [string]$DeviceSerial = ''
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Users\vuswn\Documents\Codex\.tools\android\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\Users\vuswn\Documents\Codex\.tools\android-sdk'
$env:GRADLE_USER_HOME = 'C:\Users\vuswn\Documents\Codex\.tools\gradle-user-home'
if ($Action -eq 'Install' -and [string]::IsNullOrWhiteSpace($DeviceSerial)) { throw 'Install requires -DeviceSerial for the intended phone.' }
if ($DeviceSerial) { $env:ANDROID_SERIAL = $DeviceSerial }
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
$gradleTask = if ($Action -eq 'Install') { ':app:installDefaultDebug' } else { ':app:assembleDefaultDebug' }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat $gradleTask --console=plain --max-workers=4
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}

