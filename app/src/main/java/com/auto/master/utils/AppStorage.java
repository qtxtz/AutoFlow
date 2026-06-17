package com.auto.master.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class AppStorage {
    private static final String TAG = "AppStorage";

    private AppStorage() {
    }

    public static File getAppFilesRoot(Context context) {
        Context appContext = context.getApplicationContext();
        File external = appContext.getExternalFilesDir(null);
        if (external != null) {
            return external;
        }
        File internal = appContext.getFilesDir();
        if (internal != null) {
            return internal;
        }
        return appContext.getCacheDir();
    }

    public static File getAppDirectory(Context context, String childName) {
        File root = getAppFilesRoot(context);
        File dir = childName == null || childName.trim().isEmpty()
                ? root
                : new File(root, childName);
        ensureDirectory(dir);
        return dir;
    }

    public static File getProjectsRoot(Context context) {
        return getAppDirectory(context, "projects");
    }

    public static boolean ensureDirectory(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            return true;
        }
        boolean created = dir.mkdirs();
        if (!created && !dir.isDirectory()) {
            Log.w(TAG, "failed to create directory: " + dir.getAbsolutePath());
            return false;
        }
        return true;
    }
}
