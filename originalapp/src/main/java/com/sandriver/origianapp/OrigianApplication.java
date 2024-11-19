package com.sandriver.origianapp;

import android.app.Application;

public class OrigianApplication extends Application {
    public static boolean origianAppLoaded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        origianAppLoaded = true;
    }
}
