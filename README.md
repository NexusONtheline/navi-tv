# NAVI TV

> *En la actualidad. En este momento.*

La parte de [NAVI](https://github.com/NexusONtheline/navi) que vive en la tele (Android TV / Google TV).

**AVISO:** está en construcción.

## 🎙️ NAVI

Abre la app **NAVI** y te escucha directo con el micrófono del control. También le puedes escribir. Te responde con voz y en pantalla, con la misma personalidad que la NAVI de la laptop.

Puede:
- abrir apps («abre Netflix»)
- buscar en YouTube con TizenTube («pon videos de Lain»)
- cambiar el volumen
- decirte el clima (Open-Meteo)
- abrir ajustes de la tele (wifi, bluetooth, pantalla…)
- cambiar el salvapantallas («quiero paisajes en el salvapantallas»)
- explicarte paso a paso cómo hacer cosas en la tele

Usa tu propia clave gratis de Gemini. Se la puedes pasar desde la laptop con ADB:
```bash
adb shell am start -n io.github.nexusontheline.navitv/.Ajustes --es gemini_key 'TU_CLAVE' --es ciudad 'Guayaquil, Ecuador'
```
o escribirla en **NAVI → Salvapantallas → Clave de Gemini**.

**Truco:** con Button Mapper puedes hacer que una pulsación larga de un botón del control abra NAVI.

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

## ⌨️ Escribir en la tele desde la laptop

Escribir con el control es lento. Selecciona un campo de texto en la tele y desde la laptop escribe:

```bash
tv-escribir "https://safebooru.org"        # escribe
tv-escribir -b "width:>=1920 sort:random"  # borra lo que había y escribe
tv-escribir -e "serial experiments lain"   # escribe y aprieta Enter
```

Instálalo con `ln -s "$PWD/scripts/tv-escribir" ~/.local/bin/`. Usa un teclado invisible de la app NAVI, que solo está activo mientras escribe; después la tele vuelve a su teclado de siempre.
