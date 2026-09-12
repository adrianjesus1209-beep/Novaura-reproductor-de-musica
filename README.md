# Novaura - Reproductor de Musica Local

Aplicacion movil de reproduccion de musica local (MP3, WAV) construida con Kotlin y Jetpack Compose. Diseno moderno, minimalista y con soporte para modo oscuro.

## Caracteristicas

- Escaneo automatico de la biblioteca de audio del dispositivo con MediaStore
- Reproduccion con Media3 (ExoPlayer)
- Reproduccion en segundo plano con servicio en primer plano (Foreground Service)
- Notificacion multimedia con controles (reproducir, pausar, siguiente, anterior)
- Barra de progreso deslizable con tiempo actual y duracion total
- Caratula del album mostrada en la lista y en la pantalla de reproduccion (con icono por defecto si no existe)
- Controles basicos: reproducir, pausar, anterior y siguiente
- Barra "en reproduccion" anclada en la parte inferior de la lista
- Interfaz en Compose Material 3 con tema claro y oscuro segun la configuracion del sistema
- Compatible con Android 8.0 (API 26) en adelante

## APK descargable (Última versión v1.0.2)

El instalador compilado está disponible directamente en las releases principales de GitHub:

- 🚀 [Descargar Novaura v1.0.2 APK (Última Versión)](https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/latest)
- 📦 [Descarga Directa Novaura-v1.0.2.apk](https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.0.2/Novaura-v1.0.2.apk)

## Instalación

1. Descarga el archivo `apk/Novaura-v1.0.2.apk` (o descárgalo desde la página de releases).
2. Cópialo al teléfono o descárgalo directamente desde el navegador.
3. Abre el archivo desde el gestor de archivos.
4. Si Android lo pide, habilita la opcion "Instalar aplicaciones desconocidas" para el origen de la descarga.
5. Al abrir la aplicacion, concede el permiso de acceso a audio para que aparezcan tus canciones.

## Como compilar

Requisitos:

- JDK 17
- Android SDK (compileSdk 34)
- Gradle 8.7 (se usa el wrapper incluido)

Comandos:

```
gradlew.bat assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Para instalar directamente en un dispositivo conectado:

```
gradlew.bat installDebug
```

## Stack tecnologico

- Lenguaje: Kotlin 2.0
- UI: Jetpack Compose con Material 3
- Reproductor: Media3 (ExoPlayer 1.4)
- Arquitectura: MVVM con StateFlow
- Escaneo de musica: MediaStore API
- Navegacion: Navigation Compose
- Carga de caratulas: Coil

## Estructura del proyecto

```
app/src/main/java/com/novaura/music/
|-- MainActivity.kt              Punto de entrada
|-- data/                        Modelo de datos y repositorio
|-- scanner/                     Escaneo con MediaStore
|-- service/                     Foreground Service (MediaSession)
|-- viewmodel/                   PlayerViewModel con StateFlow
|-- navigation/                  Grafo de navegacion
|-- ui/theme/                    Tema claro y oscuro
|-- ui/screens/                  Pantalla de lista y de reproduccion
|-- ui/components/               Componentes reutilizables
`-- ui/utils/                    Utilidades de formato

app/src/main/AndroidManifest.xml  Permisos y declaracion del servicio
```

## Permisos utilizados

- `READ_MEDIA_AUDIO` (Android 13 y superiores)
- `READ_EXTERNAL_STORAGE` (Android 12 e inferiores)
- `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_MEDIA_PLAYBACK` para la reproduccion en segundo plano
- `POST_NOTIFICATIONS` para la notificacion multimedia

## Licencia

Uso libre para fines educativos y personales.