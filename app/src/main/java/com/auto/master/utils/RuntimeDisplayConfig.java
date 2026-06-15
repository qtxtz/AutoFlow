package com.auto.master.utils;

/**
 * 运行时可修改的 UI 显示参数。
 * 由 SetSystemParamOperationHandler 在脚本执行期间写入，由各 View 在绘制时读取。
 */
public final class RuntimeDisplayConfig {

    /** 倒计时悬浮条的填充颜色（与 DelayCountdownOverlayView 默认值一致）。 */
    public static volatile int COUNTDOWN_FILL_COLOR = 0xFFFFB3B3;

    /** 手势录制/回放覆盖层的轨迹颜色（与 GestureOverlayView 默认值一致）。 */
    public static volatile int GESTURE_STROKE_COLOR = 0x88FF0000;

    private RuntimeDisplayConfig() {}
}
