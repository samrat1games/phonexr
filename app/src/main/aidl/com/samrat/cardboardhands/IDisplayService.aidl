package com.samrat.cardboardhands;

import android.view.Surface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.ParcelFileDescriptor;

// Runs as the shell user through Shizuku: only shell may put other apps on a display PhoneXR draws.
interface IDisplayService {
    // Shizuku calls this when the service is unbound; the id is fixed by Shizuku.
    void destroy() = 16777114;

    // A virtual display that renders into surface. Returns its id, or -1.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay() = 2;
    // Starts package/activity on the virtual display. Returns an error text, or null.
    String launch(String component, int displayId) = 3;
    void injectKey(in KeyEvent event, int displayId) = 4;
    void injectMotion(in MotionEvent event, int displayId) = 5;
    // Installs a PhoneXR-prepared game through the shell service, without leaving VR for an APK UI.
    String installApk(in ParcelFileDescriptor apk, long size) = 6;
}
