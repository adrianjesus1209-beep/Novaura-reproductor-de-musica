<p align="center"><img src="logo-sin-fondo.png" width="150"></p>
<h1 align="center"><b>Novaura</b></h1>
<h4 align="center">Un reproductor de música moderno, rápido y ligero para Android.</h4>

<p align="center">
  <a href="https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.4.0.4/Novaura-v1.4.0.4.apk">
    <b>📥 Descargar APK (Novaura v1.4.0.4)</b>
  </a>
</p>

---

## 🚀 Descargas (Versión 1.4.0.4)

La versión 1.4.0.4 corrige la visibilidad inmediata del reproductor inferior y el menú de navegación al interactuar con el buscador y pantallas secundarias:
- **Visibilidad inmediata del mini reproductor al navegar:** Se sincronizó el ciclo de dibujo y estado (`onPreDraw`, `onExploreNavigate` y callbacks de `BottomSheetBehavior`) para que la barra inferior de reproducción aparezca de inmediato al entrar al buscador o abrir un resultado, sin necesidad de realizar scroll en la pantalla.
- **Ajuste dinámico de altura inferior (`peekHeight` e insets):** La barra de reproducción y el contenido ahora adaptan automáticamente su posición según si el menú de navegación inferior está presente (en Inicio) o ausente (en Búsqueda y Detalle de artista/álbum), eliminando espacios vacíos y asegurando una alineación limpia.

| Archivo | Tamaño | Enlace de Descarga |
|---|---|---|
| **Novaura v1.4.0.4 APK** | ~8.9 MB | [Descargar APK](https://github.com/adrianjesus1209-beep/Novaura-reproductor-de-musica/releases/download/v1.4.0.4/Novaura-v1.4.0.4.apk) |

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
