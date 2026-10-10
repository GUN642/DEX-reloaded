package de.chris.dexreloaded;

import android.view.Surface;

// Läuft über Shizuku als Shell (UID 2000). Feste Transaktionsnummern,
// destroy() ist von Shizuku vorgegeben.
interface IDexService {
    void destroy() = 16777114;

    int getUid() = 1;

    // Legt ein virtuelles Display an, das in die übergebene Surface rendert.
    // Gibt die Display-ID zurück.
    // keepPhoneFocus: Klicks dürfen den Systemfokus nicht vom Handy wegholen (ab Android 14).
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface, boolean keepPhoneFocus) = 2;

    void releaseVirtualDisplay(int displayId) = 3;

    void releaseAll() = 4;

    // Startet die Activity (Paket/Klasse) auf dem Display. Gibt die Ausgabe von am zurück.
    String launchApp(String component, int displayId, boolean forceStop) = 5;

    // Kurzer Statustext für die Debug-Ansicht.
    String getInfo() = 6;

    // Berührung (Finger) auf dem Display: action ist MotionEvent.ACTION_DOWN, _MOVE oder _UP.
    oneway void injectTouch(int displayId, int action, float x, float y, long downTime) = 7;

    // Mausrad an der Position (x, y). Positive v = nach oben.
    oneway void injectScroll(int displayId, float x, float y, float h, float v) = 8;

    // Taste an das Display. action ist KeyEvent.ACTION_DOWN oder _UP.
    oneway void injectKey(int displayId, int action, int keyCode, int metaState, int repeat, long downTime) = 9;

    // Holt den Systemfokus auf das Display (z. B. 0 = Handy), damit die Maus dort bleibt.
    oneway void focusDisplay(int displayId) = 10;

    // Größe eines virtuellen Displays ändern (Fenster im Breitbild).
    void resizeVirtualDisplay(int displayId, int width, int height, int dpi) = 11;
}
