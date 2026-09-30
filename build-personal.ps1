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
    if ($Action -eq 'Install') {
        $adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        $packageName = 'com.pyeonje.fadcam.beta'
        $mainPackages = & $adbPath -s $DeviceSerial shell pm list packages --user 0 $packageName
        if ($LASTEXITCODE -ne 0 -or $mainPackages -notcontains "package:$packageName") {
            throw 'The personal app is not installed in the main phone profile.'
        }
        $phoneUsers = & $adbPath -s $DeviceSerial shell pm list users
        if ($LASTEXITCODE -ne 0) { throw 'Unable to verify phone profiles.' }
        foreach ($phoneUser in $phoneUsers) {
            if ($phoneUser -match 'UserInfo\{(\d+):' -and $Matches[1] -ne '0') {
                $otherUserId = $Matches[1]
                $otherPackages = & $adbPath -s $DeviceSerial shell pm list packages --user $otherUserId $packageName
                if ($LASTEXITCODE -ne 0) { throw "Unable to verify phone profile $otherUserId." }
                if ($otherPackages -contains "package:$packageName") {
                    throw "Unexpected duplicate in phone profile $otherUserId; main-profile-only installation required."
                }
            }
        }
        Write-Output 'Verified: personal app is installed only in the main phone profile (user 0).'
    }
} finally {
    Pop-Location
}

