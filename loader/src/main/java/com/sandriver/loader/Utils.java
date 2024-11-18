package com.sandriver.loader;

import android.os.Build;

public class Utils {
    public static boolean isNewerOrEqualThanVersion(int apiLevel) {
        return Build.VERSION.SDK_INT >= apiLevel
                || ((Build.VERSION.SDK_INT == apiLevel - 1) && Build.VERSION.PREVIEW_SDK_INT > 0);
    }
}
