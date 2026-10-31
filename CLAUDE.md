# DEX reloaded

Eigene Android-App, die auf einer XR-Brille eine virtuelle Arbeitsfläche mit drei Bildschirmen erzeugt. In jedem Bildschirm läuft eine beliebige Android-App. Ersatz für Samsung DeX und Viture SpaceWalker.

## Startsignal

Nicht ungefragt mit der Umsetzung beginnen. Sobald Chris **LOS** schreibt, mit Phase 1 starten.

## Ausgangslage

1. Gerät: Samsung Galaxy S25 Ultra (SM-S938B). Laut Debug-Ansicht inzwischen Android 17, SDK 37.
2. Brille: **VITURE PRO 2 XR Glasses**, am Gerät geprüft: VID 0x35CA, PID 0x1301, ein HID-Interface mit zwei Endpunkten. Display 1920x1080 @ 60 Hz, Sichtfeld 50° diagonal.
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
2. Shizuku muss nach jedem Neustart des Handys neu gestartet werden. Die App erkennt das und zeigt einen klaren Hinweis. Ab 0.7.4 zeigt die Shizuku-Karte Wege ohne fremdes WLAN (zu Hause starten und Wireless Debugging danach aus, eigenes WLAN ohne Internet, Start per Kabel vom Laptop) und einen Knopf zu den Entwickleroptionen. Läuft Shizuku, steht dort, dass Wireless Debugging jetzt aus darf.
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

## Festlegungen ab 0.2.0 (nach erstem Gerätetest)

1. Gerätetest 0.1.2: Presentation liegt sauber über dem Spiegelbild. Virtuelles Display mit vollen scrcpy-Flags geht, Soldaten Dashboard läuft darin.
2. Wunsch von Chris: Bildschirm 2 bleibt 1920x1080 und füllt das Sichtfeld genau. Bildschirm 1 und 3 gleich groß daneben, erreichbar durch Kopfdrehen, zusätzlich per Knopf. Darum wurde das Kopf-Tracking aus Phase 3 vorgezogen.
3. Layout: drei **flache** Flächen im Bogen um den Betrachter (Abstand 1, ab 0.3.1 ohne Lücke, Kanten stoßen direkt aneinander). Bei Größe 100 % füllt eine Fläche genau das Sichtfeld (Projektion aus 50° diagonal). Einstellbar: Größe, Sichtfeld. Krümmung der einzelnen Fläche ist vorerst weggefallen, weil sie die Pixeltreue stört.
4. IMU ohne Hersteller-SDK. Das offizielle SDK (`libglasses.so`) ist proprietär und darf nicht verteilt werden. Protokoll V2 stammt aus **viture-v2** (Holger Bartnick, MIT, PROTOCOL.md), dort für genau Pro 2 / 0x1301 gemessen. Umsetzung in `imu/VitureImu.kt`: USB-Host, HID-Interface claimen, Befehl `0x0301 [1, 3]` (Pose-Strom 240 Hz), Pose-Ereignis `0x7308` mit Quaternion w, x, y, z ab Nutzlast-Byte 8, little-endian. Firmware über `0x3003`.
5. `imu/HeadTracker.kt`: Gieren und Nicken relativ zu einer Bezugsrichtung, One-Euro-Filter (in Ruhe stark, bei Bewegung schwach geglättet), Zentrieren. Geräteachsen am Gerät gemessen: hoch +Y, rechts +X (Standard ab 0.2.1). Per Ausrichten-Dialog (geradeaus, rechts drehen, hoch neigen) neu bestimmbar. Rollen wird noch nicht genutzt.
8. Die Presentation fordert den Displaymodus mit der höchsten Bildwiederholrate bei gleicher Auflösung an. Die Debug-Ansicht listet die Modi. Ab 0.7.6 (Gerätetest: Zeiger träger als in DeX): `TrackpadView` mit `requestUnbufferedDispatch(SOURCE_CLASS_POINTER)` (Ereignisse sofort statt einmal pro Bild), Presentation mit `setPreferMinimalPostProcessing` (Spielmodus bei Monitoren und Fernsehern), Renderer liest Zeiger und Kopflage spät im Bildtakt (`lateLatch`: wartet bis zu 40 % des Takts, nur wenn das Zeichnen schnell ist). Schalter „Weniger Verzögerung“ in der Karte Zeigergeschwindigkeit, Standard an. Grund für den Rest: DeX zeichnet den Zeiger als eigene Ebene im System, unser Zeiger läuft durch den ganzen GL-Takt.
6. Renderer zeichnet dauerhaft (`RENDERMODE_CONTINUOUSLY`). Kamera: Kopf plus Versatz der Knöpfe „Blick auf 1, 2, 3“.
7. USB-Filter für VID 0x35CA in `MainActivity`, damit Android die App beim Anstecken anbietet und den Zugriff dauerhaft erlaubt. Ab 0.7.5 entfernt (Wunsch von Chris: App soll sich beim Anstecken nicht mehr öffnen). USB-Zugriff für das Kopf-Tracking fragt `VitureImu` jetzt beim Start des Trackings über `UsbManager.requestPermission`.

## Festlegungen Phase 2 (ab 0.3.0)

1. Drei Bildschirme, je ein virtuelles Display (gleiche Größe und Dichte für alle, einstellbar unter „Erweitert“). App pro Bildschirm wird gemerkt (`screenApp0..2`) und beim Anzeigen der Arbeitsfläche wieder gestartet, sobald der Shizuku-Dienst läuft. Zuletzt genutzte Apps (`recentApps`, max. 10) stehen oben in der App-Auswahl.
2. Eingabe im Dienst (`IDexService`, oneway): `injectTouch` (Finger, DOWN/MOVE/UP), `injectScroll` (Maus, ACTION_SCROLL), `injectKey`. `HiddenApi.inject` nutzt `InputManagerGlobal` (ab Android 14) bzw. `InputManager`, Display-ID per `InputEvent.setDisplayId`.
3. Klicks werden als Berührung eingespeist, nicht als Maus. Das verstehen alle Apps, Ziehen scrollt Listen. Rechte Maustaste und Zwei-Finger-Tippen sind Zurück.
4. Zeiger: eigene Koordinaten (Bildschirm 0..2, Pixel im virtuellen Display), geht über die Ränder in den Nachbarn. Der Renderer zeichnet einen Pfeil davor. Ab 0.4.1 wird der Bildschirm mit Fokus nicht mehr markiert (Wunsch von Chris). Fokus wechselt beim Klicken und mit dem Blick.
5. „Zeiger folgt Blick“ (Standard an): Schaut man auf einen anderen Bildschirm, springt der Zeiger in dessen Mitte und der Fokus wechselt.
6. Trackpad ist ein Vollbild-Modus **in** `MainActivity` (keine eigene Activity), damit die Presentation sichtbar bleibt. `input/TrackpadView`: ein Finger bewegt, Tippen klickt, ruhig halten dann ziehen (langes Drücken), zwei Finger scrollen, drei Finger wischen wechselt den Bildschirm. Bluetooth-Maus per Pointer Capture. Bildschirm bleibt an, „Dunkel“ dimmt das Handy.
7. Bluetooth-Tastatur: `MainActivity.dispatchKeyEvent` leitet bei offenem Trackpad Tasten von echten Geräten an den fokussierten Bildschirm. Kürzel: Strg+Alt+Links/Rechts (Bildschirm wechseln), Strg+Alt+1/2/3, Strg+Alt+C (zentrieren).

## Fokus und Leiste (ab 0.4.2)

1. Problem aus dem Test: Ein Mausklick auf der Brille holte den Systemfokus auf das virtuelle Display, die Handy-App verlor ihn und damit Pointer Capture. Die Maus erschien wieder auf dem Handy, das Loslassen kam nicht an und aus dem Klick wurde ein langes Drücken.
2. Lösung: virtuelle Displays ab Android 14 zusätzlich mit `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED` (1 shl 16). Rückfall auf die bisherigen Flags, dann auf die Grundflags. Die verwendeten Flags stehen in der Debug-Ansicht. Wirkt erst bei neu angelegten Displays.
3. `TrackpadView.onPointerCaptureChange`: geht der Zugriff verloren, wird eine gedrückte Maustaste sofort losgelassen.
5. Gerätetest 0.4.2: Mausklicks gingen gar nicht mehr. Ursache vermutlich `STEAL_TOP_FOCUS_DISABLED`. Ab 0.4.3 ist das Flag nur noch Option („Fokus fest am Handy“ unter Erweitert, Standard aus). Standard ist jetzt: Display darf den Fokus beim Klick bekommen, 60 ms nach dem Drücken holt der Dienst ihn per `focusDisplay(0)` zurück (`IActivityTaskManager.focusTopTask`, sonst `IWindowManager.moveDisplayToTopIfAllowed`). Bei Verlust von Pointer Capture mit gedrückter Taste wartet `TrackpadView` 800 ms auf die Rückkehr, sonst Loslassen. Die Debug-Ansicht zeigt Eingaben ok/fehlgeschlagen und das Ergebnis der Fokus-Rückholung.
6. Gerätetest 0.5.0: Mausklick immer langer Klick, Maus springt oft aufs Handy. Ab 0.5.1: Maustaste schickt beim Drücken nichts. Kurzer Klick geht beim Loslassen als DOWN+UP zusammen raus (`DexController.click`), erst Halten (250 ms) oder Bewegen (8 px) startet Ziehen bzw. langes Drücken. Mausereignisse ohne Pointer Capture (in `onTouchEvent`) werden trotzdem ausgewertet und holen den Zugriff neu. Fokus-Rückholung wird nach 60, 200, 450, 900 ms wiederholt, solange das Trackpad-Fenster keinen Fokus hat, auch wenn es ihn kurz nach einem Klick verliert.
7. Gerätetest 0.5.1: Klicks gut. Nach Tippen auf der Tastatur war die Maus wieder am Handy (die App in der Brille hatte den Fokus). Ab 0.5.2: Mausbewegung ohne Pointer Capture (Hover-Ereignisse kommen auch ohne Fokus an) ruft `DexController.reclaimFocus` (höchstens alle 300 ms `focusDisplay(0)`), danach holt `onWindowFocusChanged` die Maus zurück.
4. Leiste unten (`render/NavBar`): auf dem Bildschirm mit dem Zeiger ein dünner Griff unten in der Mitte. Fährt der Zeiger hin, klappt sie auf: „Zurück“ und „Home“. Klicks darauf behandelt `DexController.handleNavClick`, sie gehen nicht an die App. Ab 0.7.2 (Wunsch von Chris, verdeckte Knöpfe der Apps unten): Leiste und Griff sitzen 150 px über dem unteren Rand (`NavBar.MARGIN`), sie klappen nur in einem Band um die Leiste auf, nicht ganz unten am Rand.

## Breitbild mit Fenstern (ab 0.5.0)

1. Wunsch von Chris: Option, die drei Bildschirme als einen breiten Bildschirm zu nutzen, Apps in verschieb- und skalierbaren Fenstern. Schalter in der Karte „Bildschirme“, Standard aus (`wideMode`).
2. Umsetzung ohne Freeform-Modus von Android, alles selbst gezeichnet (`desk/WideDesk.kt`): Hintergrund ist ein virtuelles Display mit 3 x Breite (5760x1080), darauf läuft der Startbildschirm. Jede App bekommt ein eigenes virtuelles Display in Fenstergröße (Standard 1280x800) und hält sich dort für Vollbild.
3. Renderer: Bildquellen als Map (0..2 Bildschirme, `BG_ID` 10 Hintergrund, Fenster ab 100). Im Breitbild zeigt jede Fläche ihren Drittel-Ausschnitt des Hintergrunds (`uUvRect` im Vertex-Shader). Fenster werden über den Flächen gezeichnet und an den Bildschirmkanten geteilt (`drawCanvasRect`). Titelleiste 40 px mit Name, Maximieren, Schließen (`render/WindowChrome`), Griff unten rechts 36 px.
4. Bedienung: Titelleiste ziehen verschiebt, Griff ziehen ändert die Größe (beim Loslassen `VirtualDisplay.resize` und neue Puffergröße), Maximieren füllt den Bildschirm, auf dem das Fenster überwiegend liegt. Klick in ein Fenster holt es nach vorn und gibt ihm den Tastaturfokus. Klick daneben geht an den Hintergrund.
5. Apps aus dem Startbildschirm öffnen im Breitbild ein Fenster auf dem gewählten Bildschirm (normaler Klick: Mitte). Dieselbe App ein zweites Mal holt das vorhandene Fenster nach vorn. Die Leiste mit Zurück und Home ist im Breitbild aus.
6. Fensterpositionen werden ab 0.6.0 gemerkt (siehe Version 0.6.0).
7. Ab 0.5.1 (Wunsch von Chris): Breitbild ist eine durchgehend gebogene Fläche (Zylinder, Radius 1) statt drei Facetten. Pixelwinkel wie in der Mitte eines flachen Bildschirms (`pixelAngle = 2 * halbeBreite / 1920`), damit gleich scharf. Gezeichnet als Streifen mit Abschnitten von 48 px (`drawStrip`). `panelStepDeg` im Breitbild ist eine Bildschirmbreite als Bogen.
10. Gerätetest 0.5.2: Fenster scharf, aber der ganze Startbildschirm im Breitbild pixelig. Vermutung: Android/One UI begrenzt die Oberflächenbreite (z. B. `config_maxUiWidth`) und rechnet das 5760er Display herunter. Ab 0.5.3 gibt es kein so breites Display mehr: Der Startbildschirm läuft auf einem normalen 1920x1080-Display in der Mitte der Fläche (`WideDesk`, Treffer und Scrollen nur dort). Das breite Hintergrundbild zeichnet der Renderer selbst als Textur über die ganze gebogene Fläche (`setWideWallpaper`), der Startbildschirm zeigt im Breitbild genau dessen mittleres Drittel (`HomeStore.loadWideCenter`), dadurch nahtlos. Ohne eigenes Bild wird ein Standardverlauf in voller Breite gemalt.
9. Icons im Startbildschirm und in der App-Auswahl werden ab 0.5.2 genau in Anzeigegröße gezeichnet (`LocalDensity`), mit `FilterQuality.High`. Vorher 144 px geladen und grob verkleinert, das sah verpixelt aus.
8. Hintergrundbild wird zusätzlich als 5760x1080 gespeichert (`wallpaper_wide.jpg`), der Startbildschirm nimmt auf breiten Displays diese Fassung. Vorher wurde das 1920er Bild dreifach gestreckt, das sah unscharf aus.

## Handy-Tastatur und Aufnahme (ab 0.5.4)

1. Die Handy-Tastatur im Trackpad lief als sichtbares Passwortfeld, daher ohne Vorschläge und Korrektur. Ab 0.5.4 normales Textfeld mit `TYPE_TEXT_FLAG_AUTO_CORRECT` und `CAP_SENTENCES`. Zwischentexte (Composing) gibt `ForwardingConnection` als Differenz weiter (gemeinsamen Anfang behalten, Rest löschen, neu tippen). Korrekturen früherer Wörter, die Text vor dem Cursor bräuchten, gehen nicht, weil die App den Text der Ziel-App nicht kennt.
3. Gerätetest 0.5.4: Korrektur wird auf der Tastatur angezeigt, kommt aber nicht in der Brille an. Ursache: Die Verbindung hatte keinen Text, die Tastatur sah vor dem Cursor nichts. Ab 0.5.5 ist `ForwardingConnection` ein echtes Textfeld (`BaseInputConnection` mit fullEditor) auf einem Puffer in `TrackpadView`. Die Tastatur liest, korrigiert und ersetzt darin wie in jedem Feld (`setComposingRegion`, `deleteSurroundingText`, `commitCorrection`). Nach jeder Änderung (bzw. Ende eines Batch) schickt `syncText` die Differenz an die Ziel-App: gemeinsamen Anfang und Ende behalten, Rest mit Entf löschen und neu tippen, Pfeiltasten nur wenn der Cursor nicht am Ende steht. Cursor und Zwischentext werden per `updateSelection` gemeldet. Klick, Fokuswechsel, Enter oder Bluetooth-Taste leeren den Puffer (`DexController.onTextContextLost`).
2. Bildschirmaufnahme der Brille: Knopf „Aufnahme“ im Trackpad und in der Karte Brille. Der Shizuku-Dienst startet `screenrecord --display-id <physische ID>` (über `Display.getAddress()`, sonst `dumpsys SurfaceFlinger --display-id`), 16 Mbit/s, ohne Ton. Nach 3 Minuten beendet sich screenrecord, dann folgt automatisch der nächste Teil. Stopp per SIGINT. Dateien: `/sdcard/Movies/DEX-reloaded/DEX_<Zeit>_<Teil>.mp4`, danach MediaScanner.

## Version 0.6.0 (Wunschliste von Chris)

1. **Weitere Bildschirme** (`render/ScreenLayout`): 0, 1, 2 Grundreihe, dazu wahlweise 3 oben (über 2, Unterkante an Oberkante), 4 links außen, 5 rechts außen (`extraScreen3..5`). Lage als Spalte und Reihe, `panelPoint` kippt erst um die Querachse, dann um die Hochachse. Blick, Einrasten und „Zeiger folgt Blick“ suchen den nächstgelegenen Bildschirm in beiden Richtungen. Zeiger geht über alle Ränder in Nachbarn, auch nach oben. Strg+Alt+Hoch/Runter, Strg+Alt+4/5/6. Breitbild bleibt drei Bildschirme breit (`WIDE_PANELS`).
2. **Kinomodus** (`cinema`, Strg+Alt+K, Knopf im Trackpad): nur der angeschaute Bildschirm, fest geradeaus, Größe eigen (`cinemaSize`, Standard 85 %). ◀ ▶ wechseln den gezeigten Bildschirm. Nur ohne Breitbild.
3. **Bildschirmfoto** (Knopf „Foto“, Strg+Alt+S): `screencap -d <physische ID> -p` im Dienst, nach `Bilder/DEX-reloaded`, danach Einblendung.
4. **Einblendungen** im Renderer (`Overlay`, Plätze Toast und Umschalter), Bilder 1:1 in Pixeln aus `render/OverlayBitmaps`. Toast oben auf dem angeschauten Bildschirm, 6 s, Klick öffnet, Kreuz rechts schließt (`DexController.handleOverlayClick`).
5. **Benachrichtigungen** (`notify/`): `NotifyService` (NotificationListenerService) füllt `Notifications`. Ohne Gruppen-Zusammenfassungen und laufende (außer Anrufe). Neue oder geänderte (nicht „nur einmal melden“, passend zum System-Nicht-stören) werden eingeblendet, eigener Schalter „Nicht stören“ und „Einblenden“. Öffnen: `contentIntent` per `PendingIntent.send` mit `setLaunchDisplayId` auf den Bildschirm mit dem Zeiger, im Breitbild ins Fenster der App bzw. neues Fenster. Leerer Bildschirm: erst App starten, dann die Absicht. Antworten über die Aktion mit `RemoteInput`. Liste über die Glocke in der Statusleiste des Startbildschirms. Zugriff muss Chris am Handy erlauben, bei installierten APKs eventuell erst „Eingeschränkte Einstellungen zulassen“.
6. **Taskleiste und Umschalter**: Dienst liefert `getTasks` (IActivityTaskManager, braucht REAL_GET_TASKS) und `moveTaskToFront` (sonst `am start` der Basis-Activity). `DexController` fragt alle 2 s ab (`tasks`, `runningApps`, nur eigene Displays). Dock: Punkt unter laufenden Apps, nicht angeheftete laufende Apps hinter einem Strich. Klick im Dock wechselt zur laufenden App (Blick dorthin, nach vorn), sonst öffnen. Knopf „⇄ Apps“: vorige App auf dem Bildschirm mit Fokus. Alt+Tab (fängt Android eventuell selbst ab, dann Strg+Alt+Tab): Umschalter mit Icons, Loslassen wechselt, Esc bricht ab. Im Breitbild über die Fenster.
7. **Arbeitsplätze** (`workspace/Workspaces`, JSON in `workspaces`): Name, Breitbild, weitere Bildschirme, Kino, App pro Bildschirm, Fenster mit Lage. Karte am Handy (laden, überschreiben, löschen, speichern) und Auswahl links in der Statusleiste des Startbildschirms. Breitbild-Fenster werden ab jetzt immer gemerkt (`wideWindows`) und beim Start des Breitbilds wieder geöffnet.

## Kinosaal (ab 0.6.2)

Wunsch von Chris: Kinomodus als immersiver Kinosaal. `render/CinemaScene` zeichnet um die Leinwand (Abstand 12 m, gleiche Winkelgröße wie vorher, fest im Raum, Kopf-Tracking im Kino immer an) einen Saal aus einfacher Geometrie mit Farben je Ecke: ansteigende Sitzreihen (versetzt, rote Samtsitze), Wände mit Paneelen und Wandleuchten, Decke mit Sternenlichtern, Stufenlichter, Projektorfenster mit schwachem Lichtkegel, roter Vorhang mit Falten und Volant. Licht kommt von der Leinwand, beim Bauen eingerechnet. Beim Einschalten ist der Vorhang zu, öffnet sich in etwa 5 s, das Saallicht dimmt dabei herunter. Publikum: Köpfe und Schultern in den Reihen, aber keiner, der die Leinwand verdeckt. Beim Blick nach unten: eigene Beine (Jeans, Schuhe), Armlehnen, Popcorn-Eimer auf dem Schoß, Becher mit Strohhalm im Halter rechts. Gemalte Figuren (`render/CinemaArt`): Nachbarn links und rechts im Profil, rechts mit XR-Brille wie die eigene. Überraschungen: Schaut man den linken Nachbarn knapp 2 s an, dreht er sich um und flüstert „PSSST!“. Eine Katze schaut drei Reihen vorn über die Lehne, weiter vorn rechts sitzt ein Außerirdischer. Notausgang-Schild an der linken Wand.

Ab 0.6.4 (Gerätetest 0.6.3: zu hell, Sitze zu eckig, Wunsch nur Star-Wars-Zuschauer): Bilder und Texturen sind PNG in `res/drawable-nodpi/cinema_*.png`, erzeugt mit `tools/cinema_art.py` (PIL, selbst gezeichnete Fan-Art, Vorschau lokal möglich). Szene in einem Shader mit Textur, Farbe und Licht je Ecke, weiche Schattierung über Ecken-Normalen. Sitze als gerundete, gepolsterte Platten mit Samt-Textur und Steppnähten, leicht nach hinten geneigt, runde Armlehnen. Teppich, Wandstoff, Samtvorhang als Kacheltexturen mit Mipmaps. Beine, Popcorn-Eimer und Becher als runde Röhren. Weniger Grundlicht, Bühnenlicht für den Vorhang. Zuschauer (Darth Vader, Sturmtruppler, Yoda, Chewbacca, R2-D2, C-3PO, Leia, Boba Fett) als Bilder von hinten, die sich zum Betrachter drehen. Nachbarn: links Yoda (dreht sich nach knapp 2 s Ansehen um: „Leise sein, du musst!“), rechts Darth Vader mit Popcorn. Ein Jawa mit leuchtenden Augen schaut drei Reihen vorn über die Lehne. CinemaArt (Canvas) entfällt. Das Repo ist öffentlich, die Fan-Art liegt darin.

## Monitor-Modus (ab 0.7.0)

Wunsch von Chris: auch normale externe Bildschirme. Anzeigeart in der Karte „Anzeige“: Auto (Viture per USB angesteckt, VID 0x35CA, dann Brille, sonst Monitor), immer Brille, immer Monitor (`displayMode`). Entschieden wird beim Start der Arbeitsfläche (`monitorActive`). Monitor: `WorkspaceRenderer.monitorMode` zeichnet flach mit Orthoprojektion in Pixeln des Bildschirms, ohne Tiefe, kein Kopf-Tracking, kein IMU, kein Kino. Virtuelle Displays in der echten Auflösung des Monitors (`vdConfig`), Dichte = Höhe x 0,185 x Größe (80, 100, 125, 150 %, `monitorScale`, 1080p ergibt 200 dpi, 4K 400 dpi). Ohne Breitbild sind die Bildschirme Arbeitsflächen nebeneinander, sichtbar ist die gewählte, Wechsel mit ◀ ▶, Strg+Alt+Pfeilen oder drei Fingern, kurzes Schieben (`slidePos`). Der Zeiger bleibt auf der Arbeitsfläche. Breitbild auf dem Monitor: `WideDesk` mit einer Fläche (`panels = 1`), Startbildschirm füllt den Monitor, Fenster darüber, Fensterlage getrennt gemerkt (`wideWindowsMonitor`). Aktive Bildschirme dort nur `1`, Zeiger-x ist `(Bildschirm - 1) * Breite + x`. Ab 0.7.1 (Wunsch von Chris, zum Testen ohne Brille): Kino auch auf dem Monitor, dann 3D mit 80° Sichtfeld, Maus und Trackpad drehen den Blick (`addMouseLook`, 0,08° je Pixel), kein Zeiger, Klicks gehen nicht an die App, „Mitte“ schaut wieder nach vorn. Autostart und Schnelleinstellungs-Kachel aus Phase 4 hat Chris verworfen. Ab 0.7.3 (Wunsch von Chris: NexDock und jedes andere Gerät): Monitor-Modus gilt für jedes externe Display, auch Laptop-Docks. Tastatur kommt über `dispatchKeyEvent` (jede echte Tastatur), Touchpad über Pointer Capture: Touchpads liefern dort absolute Fingerpositionen (`SOURCE_TOUCHPAD`), `TrackpadView.capturedTouchpad` rechnet sie in Bewegung um (volle Breite etwa 1200 px, Mausgeschwindigkeit), Tippen klickt, zwei Finger scrollen, Tippen mit zwei Fingern ist Zurück, Klicktaste wie Maus. Trackpad am Handy muss dafür offen sein.

## Design (ab 0.6.1)

Wunsch von Chris: Nothing-Stil wie in seiner App Void Files (Repo `GUN642/File-manager`). Schwarz, Grautöne `#0E0E0E`/`#1A1A1A`, Text `#F2F2F2`, gedämpft `#8C8C8C`, Akzent Nothing-Rot `#D71921`. Schriften Doto (Punktmatrix, Überschriften und Uhr) und Space Mono (Beschriftungen in Großbuchstaben), beide SIL OFL, Lizenzen in `licenses/`. Bausteine in `ui/Theme.kt`: `Dex` (Farben), `Label`, `Pill`, `StatusDot`, `DotGrid`, `DotBar`, `dotHeading`. Handy: Kopf mit Punktraster und großem „DEX“, Statuszeile (Shizuku, Brille, Tracking), großer Startknopf, Karten mit Label darüber. Trackpad: Punktraster, das unter dem Finger aufleuchtet (beim Halten rot), Tasten als dunkle Flächen. Brille: `render/GlassFonts` für Bilder, Startbildschirm mit Punktmatrix-Uhr, Statusleiste in Space Mono mit Akku als Punktbalken, Standard-Hintergrund Schwarz mit Punktraster, Einblendungen, Leiste, Fensterleisten und Testbild im gleichen Stil. App-Icon: drei Bildschirme als Punktmatrix mit rotem Punkt.

## Einrasten (ab 0.4.1)

Option „Einrasten“ in der Karte Kopf-Tracking, Standard aus. Das Bild steht still auf einem Bildschirm (kein Nicken, kein Gieren), wie ohne Kopf-Tracking. Geht der Blick mehr als 0,6 Bildschirmbreiten von dessen Mitte weg, gleitet die Ansicht zum Nachbarn. Rückweg ebenso, dadurch eingebaute Hysterese. Umsetzung `WorkspaceRenderer.snapYaw`.

## Startbildschirm (ab 0.4.0)

1. Wunsch von Chris: der mittlere Bildschirm als Startbildschirm eines eigenen Systems (Statusleiste, Dock, Hintergrund).
2. Umsetzung als eigene Activity `home/HomeActivity` (Compose), die wie jede App per `am start` auf ein virtuelles Display gelegt wird (exportiert, `singleTask`, eigene `taskAffinity`). Sie läuft im App-Prozess und ruft `DexController` direkt auf. Zeiger und Tastatur funktionieren dadurch wie bei jeder App.
3. Inhalt: Statusleiste (Datum, Uhr, Brille und Tracking, Netz, Handy-Akku), große Uhr (Bereich „Zuletzt genutzt“ ab 0.4.3 entfernt), Dock mit angehefteten Apps (`dockApps`) und „Alle Apps“ mit Suche. Gedrückt halten auf ein Icon: auf Bildschirm 1, 2 oder 3 öffnen, ans Dock heften oder lösen.
4. Der Startbildschirm ist als App „Startbildschirm“ (Gruppe „DEX reloaded“) in der App-Auswahl wählbar, aber nur auf einem Bildschirm. Auf seinem Bildschirm öffnen Apps darüber, er bleibt die Grundlage. Zurück mit „Home“ im Trackpad oder Strg+Alt+H.
5. Einmalige Umstellung (`homeMigrated`): Startbildschirm auf Bildschirm 2, die bisherige App von Bildschirm 2 wandert auf Bildschirm 1, falls der leer ist.
6. Hintergrundbild: per Fotoauswahl auf dem Handy, auf 1920x1080 zugeschnitten in `files/wallpaper.jpg`. „Vom Handy“ versucht `WallpaperManager`, das verweigert Android meist. Sonst Standardverlauf.
7. Eigene App wird nie per `force-stop` beendet.
9. Dock-Reihenfolge (ab 0.5.4): im Menü eines angehefteten Icons „Im Dock nach links/rechts“ (`HomeStore.move`).
8. Uhr (ab 0.5.3): lange drücken startet Bearbeiten (Rahmen). Ziehen verschiebt, Griff unten rechts ändert die Größe (Faktor 0,4 bis 3, über die Schriftgröße), Klick daneben beendet. Gespeichert als Anteil der Fläche (`clockX`, `clockY`, `clockScale`).

## Stand

Phase 1 auf dem Gerät bestätigt. Gerätetest 0.2.0: Ansicht folgt richtig (nach Ausrichten: hoch +Y, rechts +X), kaum Drift, Pose-Rate etwa 200 Hz. Leichtes Stottern und unscharfer Text während der Drehung, 0.2.1 bringt dafür One-Euro-Filter und höchste Bildwiederholrate (noch ungetestet). Gerätetest 0.3.0: Trackpad gut, etwas zu empfindlich, Halten und Ziehen geht. Maus und Tastatur noch ungetestet. 0.3.1: Bildschirme ohne Lücke, Fokusrahmen dünn und rot, Zeigergeschwindigkeit für Trackpad und Maus einstellbar (Standard Trackpad 1,6, Maus 1,3), Knopf „Tastatur“ im Trackpad. Gerätetest 0.4.0: alles läuft. 0.4.1: Fokusrahmen entfernt, Option „Einrasten“ (Schwelle passt laut Chris). Test Maus: Klick holte die Maus zurück aufs Handy. 0.4.2: Fokus bleibt am Handy, Leiste mit Zurück und Home auf der Brille. Test 0.4.2: Mausklicks gehen nicht mehr. 0.4.3: Fokus-Rückholung statt Flag, „Zuletzt genutzt“ entfernt. Gerätetest 0.5.0: Breitbild geht, aber unscharf; Maus: immer langer Klick, springt oft aufs Handy. 0.5.1: Klick beim Loslassen, Fokus-Rückholung mit Wiederholung, Breitbild gebogen, breites Hintergrundbild. Gerätetest 0.5.1: Breitbild hervorragend, Icons verpixelt, Maus nach Tippen am Handy. 0.5.2: scharfe Icons, Maus kommt bei Bewegung zurück. Gerätetest 0.5.2: Startbildschirm im Breitbild ganz pixelig, Fenster scharf; Wunsch Uhr verschieb- und skalierbar. 0.5.3: Startbildschirm im Breitbild nur in der Mitte mit normaler Auflösung, Hintergrund vom Renderer, Uhr bearbeitbar. Gerätetest 0.5.3: Startbildschirm bestens. 0.5.4: Dock-Reihenfolge, Autokorrektur der Handy-Tastatur, Bildschirmaufnahme der Brille. Gerätetest 0.5.4: Korrektur kommt nicht in der Brille an. 0.5.5: Handy-Tastatur arbeitet auf einem Textpuffer, Korrekturen gehen als Differenz an die Brille. Aufnahme und Dock-Reihenfolge laut Chris hervorragend. 0.6.0: Benachrichtigungen, Arbeitsplätze, Taskleiste und Umschalter, Kinomodus, Bildschirmfoto, weitere Bildschirme. 0.6.1: Design im Nothing-Stil wie Void Files. 0.6.2 und 0.6.3: Kinomodus als Kinosaal (0.6.3 etwas helleres Grundlicht, weil Schwarz in der Brille durchsichtig ist). Gerätetest 0.6.3: zu hell, Sitze eckig. 0.6.4: Kinosaal mit Texturen, runden Sitzen, Star-Wars-Publikum, dunkler. 0.7.0: Monitor-Modus für normale externe Bildschirme. Gerätetest 0.7.0: laut Chris passt alles (Monitor-Modus). 0.7.1: Kino auf dem Monitor zum Testen mit Maus-Blick. Gerätetest 0.7.1: laut Chris alles super (Kinosaal auf dem Monitor angesehen). 0.7.2: Leiste mit Zurück und Home höher gesetzt. 0.7.3: Touchpads (z. B. NexDock) unter Pointer Capture. 0.7.4: Hinweise zum Shizuku-Start unterwegs. 0.7.5: App öffnet sich beim Anstecken nicht mehr. Gerätetest: Eingabe spürbar träger als DeX. 0.7.6: weniger Eingabeverzögerung.
