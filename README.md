<p align="center"><img src="logo-sin-fondo.png" width="150"></p>
<h1 align="center"><b>Novaura</b></h1>
<h4 align="center">Un reproductor de música moderno, rápido y ligero para Android.</h4>

<p align="center">
  <a href="https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.3.4/Novaura-v1.3.4.apk">
    <b>📥 Descargar APK (Novaura v1.3.4)</b>
  </a>
</p>

---

## 🚀 Descargas (Versión 1.3.4)

La versión 1.3.4 optimiza el rendimiento y refactoriza el ciclo de vida del escáner:
- **Sin escaneo automático al inicio:** Se elimina el escaneo forzado en segundo plano al arrancar la app, implementando inicialización diferida (*lazy loading*) y vinculando la indexación a acciones explícitas del usuario o condiciones de permiso.
- **Rendimiento optimizado del escáner:** Se elimina la contención de bloqueos Mutex en lecturas concurrentes de caché de base de datos, se fusionan etapas paralelas del pipeline de exploración y se minimiza la asignación de memoria innecesaria en la lectura de MediaStore.

| Archivo | Tamaño | Enlace de Descarga |
|---|---|---|
| **Novaura v1.3.4 APK** | 8.8 MB | [Descargar APK](https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.3.4/Novaura-v1.3.4.apk) |

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
