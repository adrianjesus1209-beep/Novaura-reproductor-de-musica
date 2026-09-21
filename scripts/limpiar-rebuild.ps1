# Clean build + full reinstall of Novaura (borra caché y APK antiguo para que no se vea el icono viejo).
# Uso:  powershell -ExecutionPolicy Bypass -File .\scripts\limpiar-rebuild.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    "== gradlew clean =="
    & .\gradlew.bat clean
    if ($LASTEXITCODE -ne 0) { throw "gradlew clean fallo (exit $LASTEXITCODE)" }

    "== gradlew :app:assembleDebug =="
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug fallo (exit $LASTEXITCODE)" }

    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK no encontrado: $apk" }

    $haveAdb = (Get-Command adb -ErrorAction SilentlyContinue) -ne $null
    if ($haveAdb) {
        "== desinstalando instalaciones previas =="
        & adb uninstall com.novaura.music.debug 2>$null | Out-Null
        & adb uninstall com.novaura.music 2>$null | Out-Null
        "== instalando $apk =="
        & adb install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "adb install fallo (exit $LASTEXITCODE)" }
        "OK: instalado. El icono del launcher se refrescará tras el reinstall."
    } else {
        "adbe no encontrado en PATH. Instala manualmente:"
        "  adb uninstall com.novaura.music.debug"
        "  adb install -r $apk"
    }
} finally {
    Pop-Location
}