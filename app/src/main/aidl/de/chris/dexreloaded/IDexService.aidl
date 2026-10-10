package de.chris.dexreloaded;

import android.view.Surface;

// Läuft über Shizuku als Shell (UID 2000). Feste Transaktionsnummern,
// destroy() ist von Shizuku vorgegeben.
interface IDexService {
    void destroy() = 16777114;

    int getUid() = 1;

    // Legt ein virtuelles Display an, das in die übergebene Surface rendert.
    // Gibt die Display-ID zurück.
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface) = 2;

    void releaseVirtualDisplay(int displayId) = 3;

    void releaseAll() = 4;

    // Startet die Activity (Paket/Klasse) auf dem Display. Gibt die Ausgabe von am zurück.
    String launchApp(String component, int displayId, boolean forceStop) = 5;

    // Kurzer Statustext für die Debug-Ansicht.
    String getInfo() = 6;
}
