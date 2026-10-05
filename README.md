<p align="center"><img src="logo-sin-fondo.png" width="150"></p>
<h1 align="center"><b>Novaura</b></h1>
<h4 align="center">Un reproductor de música moderno, rápido y ligero para Android.</h4>

<p align="center">
  <a href="https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.3.9.2/Novaura-v1.3.9.2.apk">
    <b>📥 Descargar APK (Novaura v1.3.9.2)</b>
  </a>
</p>

---

## 🚀 Descargas (Versión 1.3.9.2)

La versión 1.3.9.2 unifica la identidad visual en todos los niveles del sistema:
- **Ícono y Splash oficiales:** Se actualizan los recursos de splash nativo y launcher (`ic_launcher_background` en negro puro `#000000`, `novaura_splash_icon` con el logo oficial) para que instaladores de cualquier fabricante (Transsion, Xiaomi, Samsung, Google) muestren el logo auténtico sobre fondo negro.
- **Escaneo único en primer inicio:** La búsqueda de archivos en el almacenamiento se realiza una única vez en la primera apertura de la app, persistiendo todos los registros en la base de datos local SQLite (`DBCache`).
- **Lectura directa desde base de datos:** En los siguientes inicios, la aplicación omite cualquier escaneo en el sistema de archivos (`MediaStore`/disco) y lee directamente la biblioteca guardada en milisegundos, sin notificaciones ni barras de escaneo.
- **Reescaneo bajo demanda explícita:** El escaneo completo del almacenamiento solo se vuelve a ejecutar si el usuario pulsa voluntariamente "Volver a escanear" en los Ajustes.

| Archivo | Tamaño | Enlace de Descarga |
|---|---|---|
| **Novaura v1.3.9.2 APK** | 8.9 MB | [Descargar APK](https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.3.9.2/Novaura-v1.3.9.2.apk) |

---

## Acerca de Novaura

Novaura es un reproductor de música local para Android diseñado para ser rápido, fiable y elegante. Basado en las bibliotecas de reproducción de medios más modernas de Android, Novaura ofrece una experiencia auditiva fluida y de alta calidad.

## Características

- Interfaz moderna e intuitiva.
- Soporte para formatos de audio avanzados.
- Gestión eficiente de bibliotecas locales y listas de reproducción.
- Reproducción fluida y consumo optimizado de recursos.

## Licencia

Este proyecto se distribuye bajo la licencia GNU General Public License v3.0.
