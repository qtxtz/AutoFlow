package com.auto.master.floatwin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.Toast;

import com.auto.master.utils.AppStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class NodeButtonImagePickerActivity extends Activity {
    static final String EXTRA_PROJECT = "project";
    static final String EXTRA_TASK = "task";
    private static final int REQ_PICK_IMAGE = 9107;

    private String projectName;
    private String taskName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT);
        taskName = getIntent().getStringExtra(EXTRA_TASK);
        openPicker();
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择按钮图片"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) {
            NodeFloatButtonUiHelper.deliverSystemPickedImage("");
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            NodeFloatButtonUiHelper.deliverSystemPickedImage("");
            finish();
            return;
        }
        try {
            String relativePath = copyPickedImage(data.getData());
            NodeFloatButtonUiHelper.deliverSystemPickedImage(relativePath);
        } catch (Exception e) {
            NodeFloatButtonUiHelper.deliverSystemPickedImage("");
            Toast.makeText(this, "选择图片失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            finish();
        }
    }

    private String copyPickedImage(Uri uri) throws Exception {
        File dir = new File(AppStorage.getProjectsRoot(this),
                safeName(projectName) + File.separator
                        + safeName(taskName) + File.separator
                        + "node_buttons" + File.separator
                        + "assets");
        AppStorage.ensureDirectory(dir);

        String ext = extensionFromName(displayName(uri));
        if (ext.isEmpty()) ext = ".png";
        File out = new File(dir, "node_btn_" + System.currentTimeMillis() + ext);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("无法读取图片");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        return "node_buttons/assets/" + out.getName();
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private String extensionFromName(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg")) return ".jpg";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".webp")) return ".webp";
        return "";
    }

    private String safeName(String value) {
        return value == null ? "" : value;
    }
}
