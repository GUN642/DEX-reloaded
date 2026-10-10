# DEX reloaded

Eigene Android-App, die auf einer XR-Brille eine virtuelle Arbeitsfläche mit drei Bildschirmen erzeugt. In jedem Bildschirm läuft eine beliebige Android-App. Ersatz für Samsung DeX und Viture SpaceWalker.

## Startsignal

Nicht ungefragt mit der Umsetzung beginnen. Sobald Chris **LOS** schreibt, mit Phase 1 starten.

## Ausgangslage

1. Gerät: Samsung Galaxy S25 Ultra, One UI 8 bzw. 8.5, Android 16.
2. Brille: laut Chris **Viture Pro 2**. Genaue Modellbezeichnung sowie USB-VID und -PID am Gerät prüfen, bevor das IMU-Protokoll festgelegt wird.
3. Shizuku ist auf dem Handy installiert und Chris bekannt.
4. DeX wird beim Anstecken der Brille nicht aktiviert. HDMI-Modus in den Samsung-Einstellungen auf Bildschirm spiegeln. Die Brille ist für Android dann ein normales externes Display.
5. Bedienung: Handydisplay als Trackpad **und** Bluetooth-Maus und -Tastatur. Beides muss gehen.

## Warum nicht SpaceWalker

Chris hat SpaceWalker getestet. Hauptproblem: Dort laufen nur wenige freigegebene Apps (YouTube, Browser), eigene Apps nicht. **Wichtigstes Ziel ist daher, dass jede installierte App läuft**, insbesondere Chris' eigene Apps (z. B. Soldaten Dashboard). Diese als Testfall verwenden.

## Architektur

### 1. Darstellung auf der Brille
Die App sucht das externe Display über `DisplayManager` (Kategorie Presentation) und zeigt darauf eine `Presentation` mit einer OpenGL-ES-3-Oberfläche. Dort werden die drei Bildschirme als Flächen gerendert, leicht gekrümmt nebeneinander, dazu Dock, App-Auswahl und Mauszeiger. Ob die Presentation im Spiegelmodus von One UI sauber über dem gespiegelten Bild liegt, als erstes auf dem Gerät verifizieren.

### 2. Fremde Apps in den Bildschirmen
Normale Apps dürfen keine fremden Apps einbetten. Deshalb läuft ein Teil der App über Shizuku mit Shell-Rechten (Shizuku `UserService`, UID 2000).

1. Pro Bildschirm eine `SurfaceTexture` im Renderer anlegen, daraus eine `Surface`.
2. Die `Surface` per AIDL an den Shizuku-Dienst geben. Der Dienst legt damit ein virtuelles Display an.
3. Apps per `am start --display <id>` bzw. `ActivityOptions.setLaunchDisplayId` auf diesem Display starten.
4. Der Renderer zeichnet die `SurfaceTexture` als externe Textur (`GL_TEXTURE_EXTERNAL_OES`) auf die jeweilige Fläche.

Referenz für virtuelle Displays mit Shell-Rechten ist **scrcpy** (Apache 2.0), Funktion `--new-display`. Flags und Vorgehen von dort übernehmen und auf One UI 8 prüfen. Optional nötige globale Einstellungen (z. B. `force_resizable_activities`) nur setzen, wenn es ohne nicht geht, und beim Beenden wieder zurücksetzen.

### 3. Eingabe
1. Der Mauszeiger wird vom eigenen Renderer gezeichnet. Treffertest Zeiger gegen Fläche ergibt Bildschirm und lokale Koordinaten.
2. Klicks, Ziehen und Scrollen als `MotionEvent` mit gesetzter Display-ID über `InputManager.injectInputEvent` im Shizuku-Dienst einspeisen. Tastatureingaben genauso als `KeyEvent` an den fokussierten Bildschirm.
3. Trackpad: Vollbild-Activity auf dem Handy, relative Bewegung, Tippen ist Klick, zwei Finger scrollen.
4. Bluetooth-Maus: In der Handy-Activity per Pointer Capture relative Bewegungen abgreifen und an den eigenen Zeiger geben.
5. Bluetooth-Tastatur: Tastenevents in der Handy-Activity abfangen und an den fokussierten Bildschirm weiterreichen. Bildschirmtastatur auf den virtuellen Displays ist ein bekanntes Risiko, in Version 1 reicht Hardware-Tastatur.

### 4. 3DoF (Phase 3)
1. IMU-Daten der Brille über die Android USB-Host-API (`UsbManager`, HID-Interface) auslesen.
2. Protokoll bevorzugt aus dem offiziellen Viture SDK. Alternativ als Referenz **XRLinuxDriver** (wheaney), das Viture-Brillen unterstützt. Lizenz vor Übernahme von Code prüfen.
3. Modi: kopffest (Standard, Phase 1 und 2) und raumfest mit 3DoF. Recentern per Knopf im Trackpad und per Tastenkürzel. Glättung gegen Zittern, Drift-Ausgleich.

## Bekannte Grenzen

1. Apps mit `FLAG_SECURE` oder DRM (Banking, Netflix und Co.) zeigen auf virtuellen Displays schwarz. Das ist Systemverhalten und nicht behebbar.
2. Shizuku muss nach jedem Neustart des Handys neu gestartet werden. Die App erkennt das und zeigt einen klaren Hinweis.
3. Manche Apps verhalten sich auf Zweitdisplays eigen (feste Ausrichtung, keine Größenänderung). Solche Fälle sammeln und pro App behandeln.

## Phasen

1. **Grundgerüst.** Projekt, GitHub-Actions-Build mit Release, Shizuku-Anbindung mit Statusanzeige, Presentation auf der Brille mit drei Testflächen, ein virtuelles Display mit einer gestarteten App.
2. **Arbeitsfläche.** Drei Bildschirme mit je einer App, App-Auswahl, Fokuswechsel, Trackpad und Bluetooth-Maus und -Tastatur, Layout und zuletzt genutzte Apps merken.
3. **3DoF.** IMU auslesen, raumfester Modus, Recentern, Einstellungen für Abstand, Krümmung und Größe.
4. **Feinschliff.** Automatischer Start beim Anstecken der Brille, Schnelleinstellungs-Kachel, Design, Fehlerbehandlung.

Nach jeder Phase liefert der Build eine installierbare APK, die Chris auf dem Gerät testet.

## Technik

1. Kotlin, Gradle mit Kotlin DSL, Jetpack Compose für die Oberfläche auf dem Handy, OpenGL ES 3 für die Brille.
2. `minSdk` 31, `targetSdk` aktuell. Primäres Testgerät ist das S25 Ultra.
3. Shizuku über `dev.rikka.shizuku:api` und `provider`. Hidden APIs im Dienst per Reflection, gekapselt in einer eigenen Klasse.
4. Debug-Ansicht in der App mit Displays, virtuellen Displays, Shizuku-Status und angeschlossenen USB-Geräten (VID, PID, Interfaces). Hilft beim Einrichten der Brille.

## Build und Auslieferung (wie in Chris' anderen Projekten)

1. Workflow `.github/workflows/build-apk.yml`, startet bei Push auf `main` sowie von Hand. Pushes auf andere Branches werden nur gebaut (Kontrolle und Artefakt, Debug-Signatur falls kein Keystore), ein Release entsteht nur von `main`. Grund: In der Claude-Code-Umgebung gibt es kein Android-SDK, der Bau wird über Actions geprüft. Änderungen nur an `.md`-Dateien lösen keinen Bau aus (`paths-ignore: "**.md"`). `concurrency` bricht ältere laufende Bauten ab.
2. Ergebnis ist ein **GitHub Release** mit fertiger APK. Dateiname immer gleich: `DEX-reloaded.apk`, ohne Versionsnummer.
3. **Bei jeder Änderung `versionCode` und `versionName` hochsetzen.** Das Release-Tag kommt aus der Version. Der App-Name enthält die Version („DEX reloaded 0.1.2“), erzeugt per `resValue` in `app/build.gradle.kts`.
4. Claude darf selbst nach `main` mergen, wenn der Bau auf dem Branch grün ist. Danach den Release-Link direkt im Chat posten.
5. Die App prüft beim Start GitHub Releases (`releases/latest`) auf eine neuere Version, lädt `DEX-reloaded.apk` und startet den System-Installer (`update/Updater.kt`). Darum muss das Release-Tag immer `v<versionName>` sein.
6. Feste Signatur, damit Updates über die installierte Version gehen. Keystore einmalig erzeugen und als Repository-Secret hinterlegen. Chris dabei genau sagen, welche Secrets er anlegen muss.

## Zusammenarbeit

1. Kommunikation und App-Oberfläche auf Deutsch.
2. In Texten für Chris keine Gedanken- oder Spiegelstriche, lieber kurze Sätze.
3. Chris testet auf dem Gerät und meldet Ergebnisse. Unklare Gerätezustände über die Debug-Ansicht klären statt zu raten.
4. Diese Datei bei Architekturentscheidungen und Phasenfortschritt aktuell halten.

## Technische Festlegungen (Phase 1)

1. Paket und `applicationId`: `de.chris.dexreloaded`. Nicht mehr ändern, sonst gehen Updates nicht.
2. Versionen: AGP 8.13.0, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2025.08.00, Shizuku 13.1.5, `compileSdk`/`targetSdk` 36.
3. Signatur-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`dexreloaded`), `KEY_PASSWORD`.
4. Aufbau des Codes:
   1. `service/DexService.kt`: Shizuku-UserService (AIDL `IDexService`), legt virtuelle Displays an, startet Apps per `am start --display`.
   2. `service/HiddenApi.kt`: alle versteckten APIs. `ShellContext` gibt sich als `com.android.shell` aus, `DisplayManager(Context)` per Reflection, Flags wie scrcpy `--new-display`. Ohne Rechte für die vollen Flags Rückfall auf Grundflags.
   3. `shizuku/ShizukuManager.kt`: Status (nicht installiert, nicht gestartet, Berechtigung, Dienst) und Bindung.
   4. `render/`: `GlassPresentation` mit `GLSurfaceView` (ES 3), `WorkspaceRenderer` mit drei Flächen auf einem Zylinder (Radius 4, Breite 1,6). Eine Fläche zeigt entweder ein Testbild oder eine `SurfaceTexture` (OES) eines virtuellen Displays. GL-Aufgaben laufen über eine Warteschlange in `onDrawFrame`.
   5. `DexController.kt`: Brille finden (Kategorie Presentation, Typ extern bevorzugt, eigene virtuelle Displays ausgeschlossen), Presentation, Bildschirm-Slots, Protokoll.
   6. `ui/`: Compose-Oberfläche, App-Auswahl, Debug-Ansicht mit Kopierknopf.
5. Die Presentation hängt an `MainActivity`. Wird sie geschlossen, werden alle virtuellen Displays freigegeben.
6. Virtuelle Displays laufen ohne Systemdekoration (kein Launcher, keine Bildschirmtastatur dort).

## Stand

Phase 1 umgesetzt, wartet auf Gerätetest durch Chris. Offene Punkte zum Prüfen:
1. Erscheint die Brille im Spiegelmodus in der Kategorie Presentation, und liegt die Presentation sauber über dem gespiegelten Bild?
2. Gehen die vollen scrcpy-Flags unter One UI 8 (siehe Protokoll und Debug-Ansicht)?
3. Startet die gewählte App auf dem virtuellen Display, auch wenn sie schon auf dem Handy läuft?
4. VID und PID der Brille aus der Debug-Ansicht notieren.
