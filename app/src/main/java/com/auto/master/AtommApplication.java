package com.auto.master;

import android.app.Application;

import com.auto.master.utils.CrashLogger;
import com.auto.master.utils.SystemRuntimeConfig;

public class AtommApplication extends Application {

    public static AtommApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLogger.install(this);
        SystemRuntimeConfig.load(this).applyToRuntime();
    }
}
