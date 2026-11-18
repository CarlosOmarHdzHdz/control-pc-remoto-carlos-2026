package com.proyector.receiver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves one frame to the location selected in Android's document picker. */
final class ScreenCapture {
    private static final int REQUEST = 420;
    private Bitmap pending;
    private boolean writing;

    boolean available() { return pending == null && !writing; }

    void chooseLocation(Activity activity, Bitmap image) {
        if (image == null || !available()) return;
        pending = image;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/png").putExtra(Intent.EXTRA_TITLE, "Orbivia-" + stamp + ".png");
        try {
            activity.startActivityForResult(picker, REQUEST);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            pending = null;
            notify(activity, "Android no pudo abrir el selector de archivos.");
        }
    }

    void result(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST) return;
        Bitmap image = pending;
        pending = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (image == null) {
            notify(activity, "La captura ya no está disponible. Toma otra desde el visor.");
            return;
        }
        Uri location = data.getData();
        ContentResolver resolver = activity.getContentResolver();
        writing = true;
        new Thread(() -> {
            boolean completed = write(resolver, location, image);
            activity.runOnUiThread(() -> {
                writing = false;
                if (!activity.isDestroyed()) notify(activity, completed ? "Captura guardada" : "No se pudo guardar la captura.");
            });
        }, "orbivia-capture").start();
    }

    private static boolean write(ContentResolver resolver, Uri location, Bitmap image) {
        try (OutputStream output = resolver.openOutputStream(location)) {
            return output != null && image.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (IOException | SecurityException failure) {
            return false;
        }
    }

    private static void notify(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
