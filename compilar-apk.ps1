# Compila el APK firmado de CadaPeso y lo deja en la carpeta release\
# Uso: doble clic en compilar-apk.bat  (o en PowerShell: .\compilar-apk.ps1)
#
# Qué hace, paso a paso:
#  1. Busca el JDK 21 y el Android SDK (los mismos que usa App-Matematicas).
#  2. Escribe local.properties con la ruta del SDK.
#  3. La primera vez crea la firma del APK (cadapeso-release.jks). GUARDALA: sin ella no se
#     pueden instalar actualizaciones encima de la app ya instalada.
#  4. Corre Gradle (assembleRelease) con las carpetas de compilación fuera de OneDrive.
#  5. Copia el APK a release\CadaPeso-<versión>.apk

# Los errores se detectan por código de salida ($LASTEXITCODE): los programas nativos escriben
# avisos normales por stderr y PowerShell 5.1 los confundiría con errores.
$ErrorActionPreference = "Continue"
$raiz = $PSScriptRoot
$local = $env:LOCALAPPDATA

function Fallar($mensaje) {
    Write-Host ""
    Write-Host "  ERROR: $mensaje" -ForegroundColor Red
    Write-Host ""
    exit 1
}

# 1) JDK: JAVA_HOME, un jdk-2x en %LOCALAPPDATA%\Programs, o el que trae Android Studio.
$candidatosJdk = @()
if ($env:JAVA_HOME) { $candidatosJdk += $env:JAVA_HOME }
$programas = Join-Path $local "Programs"
if (Test-Path $programas) {
    $candidatosJdk += Get-ChildItem $programas -Directory | Where-Object { $_.Name -match '^jdk-(1[7-9]|2\d)' } |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName }
}
$candidatosJdk += "C:\Program Files\Android\Android Studio\jbr", (Join-Path $local "Android\Android Studio\jbr")
$jdk = $candidatosJdk | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } | Select-Object -First 1
if (-not $jdk) { Fallar "No encontré un JDK 17 o 21. Instalá Eclipse Temurin 21 o definí JAVA_HOME." }

# Android SDK
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $local "Android\Sdk")) |
    Where-Object { $_ -and (Test-Path (Join-Path $_ "platforms")) } | Select-Object -First 1
if (-not $sdk) { Fallar "No encontré el Android SDK. Instalá Android Studio o definí ANDROID_HOME." }

Write-Host "JDK: $jdk"
Write-Host "SDK: $sdk"
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:Path = (Join-Path $jdk "bin") + ";" + $env:Path

# 2) local.properties (barras normales: en .properties la "\" es un carácter especial)
Set-Content -Path (Join-Path $raiz "local.properties") -Value ("sdk.dir=" + ($sdk -replace '\\', '/')) -Encoding ascii -ErrorAction Stop

# 3) Firma (una sola vez)
$props = Join-Path $raiz "keystore.properties"
if (-not (Test-Path $props)) {
    Write-Host "Creando la firma del APK (cadapeso-release.jks)..."
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $clave = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
    & (Join-Path $jdk "bin\keytool.exe") -genkeypair -v -keystore (Join-Path $raiz "cadapeso-release.jks") -alias cadapeso `
        -keyalg RSA -keysize 2048 -validity 10000 -storepass $clave -keypass $clave `
        -dname "CN=CadaPeso, O=CadaPeso, C=AR" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fallar "keytool no pudo crear la firma." }
    Set-Content -Path $props -Encoding ascii -ErrorAction Stop -Value @(
        "storeFile=cadapeso-release.jks",
        "storePassword=$clave",
        "keyAlias=cadapeso",
        "keyPassword=$clave"
    )
    Write-Host "Firma creada. Hacé una copia de cadapeso-release.jks y keystore.properties en un lugar seguro." -ForegroundColor Yellow
}

# 4) Compilar (la primera vez Gradle descarga dependencias: puede tardar varios minutos)
Write-Host "Compilando el APK..."
$salida = Join-Path $local "cadapeso-build"
Push-Location $raiz
try {
    & (Join-Path $raiz "gradlew.bat") assembleRelease --console=plain --project-cache-dir (Join-Path $salida ".gradle")
    if ($LASTEXITCODE -ne 0) { Fallar "Gradle no pudo compilar el APK (revisá los mensajes de arriba)." }
} finally {
    Pop-Location
}

# 5) Copiar el resultado
$apk = Join-Path $salida "app\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { Fallar "No encontré el APK compilado en $apk" }
$version = (Select-String -Path (Join-Path $raiz "app\build.gradle.kts") -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force (Join-Path $raiz "release") -ErrorAction Stop | Out-Null
$destino = Join-Path $raiz "release\CadaPeso-$version.apk"
Copy-Item $apk $destino -Force -ErrorAction Stop
$mb = [math]::Round((Get-Item $destino).Length / 1MB, 1)
Write-Host ""
Write-Host "  APK listo: $destino ($mb MB)" -ForegroundColor Green
Write-Host "  Pasalo al teléfono e instalalo (hay que permitir 'instalar apps desconocidas')."
Write-Host ""
