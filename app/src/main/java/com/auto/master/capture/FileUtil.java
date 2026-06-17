package com.auto.master.capture;

import android.content.Context;

import com.auto.master.utils.AppStorage;

import java.io.File;

public final class FileUtil {
    private FileUtil() {}

    public static File makeCaptureFile(Context ctx, String fileName) {
        File dir = AppStorage.getAppDirectory(ctx, "captures");

        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "cap_" + System.currentTimeMillis() + ".png";
        }

        // 如果用户没写后缀，默认 png
        if (!fileName.contains(".")) fileName = fileName + ".png";

        return new File(dir, fileName);
    }
}
