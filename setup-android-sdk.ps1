# Installa l'Android SDK da riga di comando (senza Android Studio) e prepara local.properties.
# Uso:  powershell -ExecutionPolicy Bypass -File .\setup-android-sdk.ps1 [-Emulator]
#   -Emulator  installa anche emulatore + immagine di sistema x86_64 (Android 16, ~1.5 GB in piu')
#
# ATTENZIONE: lo script accetta automaticamente le licenze dell'Android SDK di Google
# (equivale a rispondere "y" a `sdkmanager --licenses`). Eseguilo solo se sei d'accordo.
param([switch]$Emulator)

$ErrorActionPreference = "Stop"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$toolsZip = "commandlinetools-win-16111833_latest.zip"
$toolsUrl = "https://dl.google.com/android/repository/$toolsZip"
$javaHome = "C:\Program Files\Java\jdk-24"

if (-not (Test-Path (Join-Path $javaHome "bin\java.exe"))) { throw "JDK non trovato in $javaHome" }
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

New-Item -ItemType Directory -Force -Path $sdk | Out-Null
$latest = Join-Path $sdk "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $latest "bin\sdkmanager.bat"))) {
    $tmp = Join-Path $env:TEMP $toolsZip
    Write-Host "Scarico $toolsUrl ..."
    Invoke-WebRequest -Uri $toolsUrl -OutFile $tmp -UseBasicParsing
    $extract = Join-Path $env:TEMP "cmdline-tools-extract"
    if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
    Expand-Archive -Path $tmp -DestinationPath $extract -Force
    New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
    Move-Item -Path (Join-Path $extract "cmdline-tools") -Destination $latest
    Remove-Item -Force $tmp
}
$sdkmanager = Join-Path $latest "bin\sdkmanager.bat"

Write-Host "Accetto le licenze SDK..."
# 30 "y" bastano per tutte le licenze note.
$yes = ("y`n" * 30)
$yes | & $sdkmanager --sdk_root="$sdk" --licenses | Out-Null

# Percorsi con "/" e non ";": cmd.exe spezzerebbe gli argomenti sul punto e virgola.
$packages = @("platform-tools", "platforms/android-36", "build-tools/36.0.0")
if ($Emulator) { $packages += @("emulator", "system-images/android-36/google_apis/x86_64") }
Write-Host "Installo: $($packages -join ', ')"
& $sdkmanager --sdk_root="$sdk" --install $packages

$props = Join-Path $PSScriptRoot "local.properties"
$sdkEscaped = $sdk -replace '\\', '\\'
"sdk.dir=$sdkEscaped" | Out-File -FilePath $props -Encoding ascii
Write-Host "Scritto $props"

if ($Emulator) {
    $avdmanager = Join-Path $latest "bin\avdmanager.bat"
    if (-not (Test-Path (Join-Path $env:USERPROFILE ".android\avd\rp_classic.avd"))) {
        # Retroid Pocket Classic: 1240x1080 @ ~420 dpi, il caso peggiore per l'altezza.
        "no" | & $avdmanager create avd -n rp_classic -k "system-images;android-36;google_apis;x86_64" -d "pixel" --force | Out-Null
        $cfg = Join-Path $env:USERPROFILE ".android\avd\rp_classic.avd\config.ini"
        Add-Content $cfg "hw.lcd.width=1240`nhw.lcd.height=1080`nhw.lcd.density=420`nhw.keyboard=yes`nhw.dPad=yes`nhw.gpu.enabled=yes`nhw.gpu.mode=auto`ndisk.dataPartition.size=6G"
        Write-Host "Creato AVD rp_classic (1240x1080 @420dpi)"
    }
}
Write-Host "Fatto. Build:  .\gradlew.bat assembleDebug"
