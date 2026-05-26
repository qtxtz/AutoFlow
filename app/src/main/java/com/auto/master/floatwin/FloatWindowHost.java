package com.auto.master.floatwin;

import android.content.Context;
import android.view.WindowManager;

import java.io.File;

/**
 * Interface defining common methods needed by extracted helper classes.
 * FloatWindowService implements this to provide access to its resources.
 */
public interface FloatWindowHost {

    /**
     * Get the Android Context
     */
    Context getContext();

    /**
     * Get the WindowManager for managing floating windows
     */
    WindowManager getWindowManager();

    /**
     * Convert dp to pixels
     */
    int dp(int dpValue);

    /**
     * Get the root directory for projects
     */
    File getProjectsRootDir();

    /**
     * Show a toast message
     */
    void showToast(String message);

    /**
     * Get the current project directory
     */
    File getCurrentProjectDir();

    /**
     * Get the current task directory
     */
    File getCurrentTaskDir();

    /**
     * 打开指定模板文件的 Mask 编辑器。
     */
    void showTemplateMaskEditorByName(String templateFileName, @androidx.annotation.Nullable Runnable onSaved);

    /**
     * 在屏幕上用覆盖层高亮显示该模板对应的搜索区域（bbox）。
     * 若 manifest 中无 bbox 则 toast 提示"全屏搜索"。
     */
    void showTemplateBboxPreview(String templateFileName);

    /**
     * 直接用给定像素坐标在屏幕上显示 bbox 高亮覆盖层。
     */
    void showRawBboxPreview(int x, int y, int w, int h);
}
