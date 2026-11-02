# NAVI TV

> *En la actualidad. En este momento.*

La parte de [NAVI](https://github.com/NexusONtheline/navi) que vive en la tele (Android TV / Google TV).

**AVISO:** está en construcción. Por ahora solo trae el salvapantallas.

## 🌌 Salvapantallas

Cuando la tele se queda quieta, muestra imágenes al azar de [Safebooru](https://safebooru.org) con un fundido suave, un zoom lento, la hora y la fecha.

- Tú eliges las etiquetas, por ejemplo `scenery` o `serial_experiments_lain`.
- Puedes poner etiquetas que nunca quieres ver.
- Puede mostrar solo imágenes horizontales, para que llenen la pantalla.
- Por defecto busca `rating:general width:>=1600`.

### Instalar

1. Baja `navi-tv.apk` de [Releases](../../releases).
2. Instálalo en la tele. Con ADB sería `adb install navi-tv.apk`.
3. Ponlo como salvapantallas en **Ajustes → Sistema → Salvapantallas → NAVI · Safebooru**. Si tu Google TV no muestra esa opción, usa ADB:
   ```bash
   adb shell settings put secure screensaver_components io.github.nexusontheline.navitv/.Salvapantallas
   ```
4. Para cambiar las etiquetas, abre la app **NAVI** desde tu lista de apps.

## 🛠️ Compilar

GitHub Actions compila el APK en cada cambio, así que no hace falta Android Studio. Para firmarlo usa dos secretos del repo: `NAVI_KEYSTORE_B64` y `NAVI_KEYSTORE_PASS`.
