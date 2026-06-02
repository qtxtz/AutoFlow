package com.auto.master.auto;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ActivityHolder implements Application.ActivityLifecycleCallbacks {

    // 弱引用：Activity 销毁后可被 GC，不阻塞内存回收
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile WeakReference<Activity> sLastRef = new WeakReference<>(null);
    private static volatile WeakReference<Activity> sResumedRef = new WeakReference<>(null);

    /** 返回最近一个 Activity；悬浮窗后台场景可能是 stopped 但未销毁的 Activity。 */
    public static Activity getTopActivity() {
        WeakReference<Activity> ref = sLastRef;
        return ref != null ? ref.get() : null;
    }

    /** 返回真正处于 resumed 状态的 Activity；用于不想拉起主界面的权限弹窗。 */
    public static Activity getResumedActivity() {
        WeakReference<Activity> ref = sResumedRef;
        return ref != null ? ref.get() : null;
    }

    public static void register(Application app) {
        if (app == null || !REGISTERED.compareAndSet(false, true)) {
            return;
        }
        app.registerActivityLifecycleCallbacks(new ActivityHolder());
        Log.i(TAG, "ActivityHolder 已注册到 Application");
    }

    @Override public void onActivityResumed(Activity activity)  {
        sLastRef = new WeakReference<>(activity);
        sResumedRef = new WeakReference<>(activity);
    }
    @Override public void onActivityStopped(Activity activity)  {
        // 注意：不在此处清除引用。
        // 本 app 以 FloatWindowService 悬浮窗为主 UI，主 Activity 长期处于 stopped（后台）但未销毁。
        // 在 stopped 时清除会导致悬浮窗内的取色/框选等功能拿不到 Activity，改为只在 destroyed 时清。
    }
    @Override public void onActivityDestroyed(Activity activity) {
        WeakReference<Activity> lastRef = sLastRef;
        if (lastRef != null && lastRef.get() == activity) sLastRef = new WeakReference<>(null);
        WeakReference<Activity> resumedRef = sResumedRef;
        if (resumedRef != null && resumedRef.get() == activity) sResumedRef = new WeakReference<>(null);
    }
    @Override public void onActivityPaused(Activity activity) {
        WeakReference<Activity> ref = sResumedRef;
        if (ref != null && ref.get() == activity) sResumedRef = new WeakReference<>(null);
    }
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
