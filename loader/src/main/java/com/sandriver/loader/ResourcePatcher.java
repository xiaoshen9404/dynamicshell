package com.sandriver.loader;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.ArrayMap;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ResourcePatcher {
    private static Collection<WeakReference<Resources>> references = null;
    private static Object currentActivityThread = null;
    private static AssetManager newAssetManager = null;
    private static Constructor<?> newAssetManagerCtor = null;
    private static Method addAssetPathMethod = null;
    private static Method addAssetPathAsSharedLibraryMethod = null;
    private static Method ensureStringBlocksMethod = null;
    private static Field assetsFiled = null;
    private static Field resourcesImplFiled = null;
    private static Field resDir = null;
    private static Field packagesFiled = null;
    private static Field resourcePackagesFiled = null;
    private static Field publicSourceDirField = null;
    private static Field stringBlocksField = null;

    private static long storedPatchedResModifiedTime = 0L;

    @SuppressWarnings("unchecked")
    public static void isResourceCanPatch() throws Throwable {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        currentActivityThread = ShareReflectUtil.getActivityThread(App.currentApp, activityThread);
        Class<?> loadedApkClass;
        try {
            loadedApkClass = Class.forName("android.app.LoadedApk");
        } catch (ClassNotFoundException e) {
            loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
        }

        resDir = ShareReflectUtil.findField(loadedApkClass, "mResDir");
        packagesFiled = ShareReflectUtil.findField(activityThread, "mPackages");
        if (Build.VERSION.SDK_INT < 27) {
            resourcePackagesFiled = ShareReflectUtil.findField(activityThread, "mResourcePackages");
        }

        final AssetManager assets = App.currentApp.getAssets();
        addAssetPathMethod = ShareReflectUtil.findMethod(assets, "addAssetPath", String.class);
        if (shouldAddSharedLibraryAssets(App.currentApp.getApplicationInfo())) {
            addAssetPathAsSharedLibraryMethod =
                    ShareReflectUtil.findMethod(assets, "addAssetPathAsSharedLibrary", String.class);
        }

        try {
            stringBlocksField = ShareReflectUtil.findField(assets, "mStringBlocks");
            ensureStringBlocksMethod = ShareReflectUtil.findMethod(assets, "ensureStringBlocks");
        } catch (Throwable ignored) {
        }

        newAssetManagerCtor = ShareReflectUtil.findConstructor(assets);

        final Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
        final Method mGetInstance = ShareReflectUtil.findMethod(resourcesManagerClass, "getInstance");
        final Object resourcesManager = mGetInstance.invoke(null);
        try {
            Field fMActiveResources = ShareReflectUtil.findField(resourcesManagerClass, "mActiveResources");
            final ArrayMap<?, WeakReference<Resources>> activeResources19 =
                    (ArrayMap<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
            references = activeResources19.values();
        } catch (NoSuchFieldException ignore) {
            // N moved the resources to mResourceReferences
            final Field mResourceReferences = ShareReflectUtil.findField(resourcesManagerClass, "mResourceReferences");
            references = (Collection<WeakReference<Resources>>) mResourceReferences.get(resourcesManager);
        }
        // check resource
        if (references == null) {
            throw new IllegalStateException("resource references is null");
        }

        final Resources resources = App.currentApp.getResources();

        try {
            // N moved the mAssets inside an mResourcesImpl field
            resourcesImplFiled = ShareReflectUtil.findField(resources, "mResourcesImpl");
        } catch (Throwable ignore) {
            // for safety
            assetsFiled = ShareReflectUtil.findField(resources, "mAssets");
        }

        try {
            publicSourceDirField = ShareReflectUtil.findField(ApplicationInfo.class, "publicSourceDir");
        } catch (NoSuchFieldException ignore) {
            // Ignored.
        }
    }

    /**
     * @param externalResourceFile
     * @throws Throwable
     */
    public static void monkeyPatchExistingResources(String externalResourceFile, boolean isReInject) throws Throwable {

        final ApplicationInfo appInfo = App.currentApp.getApplicationInfo();

        final Field[] packagesFields;
        if (Build.VERSION.SDK_INT < 27) {
            packagesFields = new Field[]{packagesFiled, resourcePackagesFiled};
        } else {
            packagesFields = new Field[]{packagesFiled};
        }
        for (Field field : packagesFields) {
            final Object value = field.get(currentActivityThread);

            for (Map.Entry<String, WeakReference<?>> entry
                    : ((Map<String, WeakReference<?>>) value).entrySet()) {
                final Object loadedApk = entry.getValue().get();
                if (loadedApk == null) {
                    continue;
                }
                final String resDirPath = (String) resDir.get(loadedApk);
                if (appInfo.sourceDir.equals(resDirPath)) {
                    resDir.set(loadedApk, externalResourceFile);
                }
            }
        }

        if (isReInject) {
            recordCurrentPatchedResModifiedTime(externalResourceFile);
            return;
        }

        newAssetManager = (AssetManager) newAssetManagerCtor.newInstance();
        // Create a new AssetManager instance and point it to the resources installed under
        if (((Integer) addAssetPathMethod.invoke(newAssetManager, externalResourceFile)) == 0) {
            throw new IllegalStateException("Could not create new AssetManager");
        }
        recordCurrentPatchedResModifiedTime(externalResourceFile);

        // Add SharedLibraries to AssetManager for resolve system resources not found issue
        // This influence SharedLibrary Package ID
        if (shouldAddSharedLibraryAssets(appInfo)) {
            for (String sharedLibrary : appInfo.sharedLibraryFiles) {
                if (!sharedLibrary.endsWith(".apk")) {
                    continue;
                }
                if (((Integer) addAssetPathAsSharedLibraryMethod.invoke(newAssetManager, sharedLibrary)) == 0) {
                    throw new IllegalStateException("AssetManager add SharedLibrary Fail");
                }
            }
        }

        if (stringBlocksField != null && ensureStringBlocksMethod != null) {
            stringBlocksField.set(newAssetManager, null);
            ensureStringBlocksMethod.invoke(newAssetManager);
        }

        for (WeakReference<Resources> wr : references) {
            final Resources resources = wr.get();
            if (resources == null) {
                continue;
            }
            try {
                //pre-N
                assetsFiled.set(resources, newAssetManager);
            } catch (Throwable ignore) {
                // N
                final Object resourceImpl = resourcesImplFiled.get(resources);
                // for Huawei HwResourcesImpl
                final Field implAssets = ShareReflectUtil.findField(resourceImpl, "mAssets");
                implAssets.set(resourceImpl, newAssetManager);
            }

            clearPreloadTypedArrayIssue(resources);

            resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
        }

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                if (publicSourceDirField != null) {
                    publicSourceDirField.set(App.currentApp.getApplicationInfo(), externalResourceFile);
                }
            } catch (Throwable ignore) {
            }
        }

        installResourceInsuranceHacks(App.currentApp, externalResourceFile);
    }

    private static void installResourceInsuranceHacks(Context context, String patchedResApkPath) {
        try {
            final Object activityThread = ShareReflectUtil.getActivityThread(context, null);
            final Field mHField = ShareReflectUtil.findField(activityThread, "mH");
            final Handler mH = (Handler) mHField.get(activityThread);
            final Field mCallbackField = ShareReflectUtil.findField(Handler.class, "mCallback");
            final Handler.Callback originCallback = (Handler.Callback) mCallbackField.get(mH);
            if (!(originCallback instanceof ResourceInsuranceHandlerCallback)) {
                final ResourceInsuranceHandlerCallback hackCallback = new ResourceInsuranceHandlerCallback(
                        context, patchedResApkPath, originCallback, mH.getClass());
                mCallbackField.set(mH, hackCallback);
            } else {
            }
        } catch (Throwable thr) {
        }
    }

    private static final class ResourceInsuranceHandlerCallback implements Handler.Callback {
        private static final String LAUNCH_ACTIVITY_LIFECYCLE_ITEM_CLASSNAME = "android.app.servertransaction.LaunchActivityItem";

        private final Context mContext;
        private final String mPatchResApkPath;
        private final Handler.Callback mOriginalCallback;

        private final int LAUNCH_ACTIVITY;
        private final int RELAUNCH_ACTIVITY;
        private final int EXECUTE_TRANSACTION;

        private Method mGetCallbacksMethod = null;
        private boolean mSkipInterceptExecuteTransaction = false;

        ResourceInsuranceHandlerCallback(Context context, String patchResApkPath, Handler.Callback original, Class<?> hClazz) {
            Context appContext = context.getApplicationContext();
            mContext = (appContext != null ? appContext : context);
            mPatchResApkPath = patchResApkPath;
            mOriginalCallback = original;
            LAUNCH_ACTIVITY = fetchMessageId(hClazz, "LAUNCH_ACTIVITY", 100);
            RELAUNCH_ACTIVITY = fetchMessageId(hClazz, "RELAUNCH_ACTIVITY", 126);

            if (Utils.isNewerOrEqualThanVersion(28)) {
                EXECUTE_TRANSACTION = fetchMessageId(hClazz, "EXECUTE_TRANSACTION ", 159);
            } else {
                EXECUTE_TRANSACTION = -1;
            }
        }

        private int fetchMessageId(Class<?> hClazz, String name, int defVal) {
            int value;
            try {
                value = ShareReflectUtil.findField(hClazz, name).getInt(null);
            } catch (Throwable e) {
                value = defVal;
            }
            return value;
        }

        @Override
        public boolean handleMessage(Message msg) {
            boolean consume = false;
            if (hackMessage(msg)) {
                consume = true;
            } else if (mOriginalCallback != null) {
                consume = mOriginalCallback.handleMessage(msg);
            }
            return consume;
        }

        @SuppressWarnings("unchecked")
        private boolean hackMessage(Message msg) {
            boolean shouldReInjectPatchedResources = false;
            if (!isPatchedResModifiedAfterLastLoad(mPatchResApkPath)) {
                shouldReInjectPatchedResources = false;
            } else {
                if (msg.what == LAUNCH_ACTIVITY || msg.what == RELAUNCH_ACTIVITY) {
                    shouldReInjectPatchedResources = true;
                } else if (msg.what == EXECUTE_TRANSACTION) {
                    do {
                        if (mSkipInterceptExecuteTransaction) {
                            break;
                        }
                        final Object transaction = msg.obj;
                        if (transaction == null) {
                            break;
                        }
                        if (mGetCallbacksMethod == null) {
                            try {
                                mGetCallbacksMethod = ShareReflectUtil.findMethod(transaction, "getCallbacks");
                            } catch (Throwable ignored) {
                                // Ignored.
                            }
                        }
                        if (mGetCallbacksMethod == null) {
                            mSkipInterceptExecuteTransaction = true;
                            break;
                        }
                        try {
                            final List<Object> req = (List<Object>) mGetCallbacksMethod.invoke(transaction);
                            if (req != null && req.size() > 0) {
                                final Object cb = req.get(0);
                                shouldReInjectPatchedResources = cb != null && cb.getClass().getName().equals(LAUNCH_ACTIVITY_LIFECYCLE_ITEM_CLASSNAME);
                            }
                        } catch (Throwable ignored) {
                        }
                    } while (false);
                }
            }
            if (shouldReInjectPatchedResources) {
                try {
                    monkeyPatchExistingResources(mPatchResApkPath, true);
                } catch (Throwable thr) {
                }
            }
            return false;
        }
    }

    private static boolean isPatchedResModifiedAfterLastLoad(String patchedResPath) {
        long patchedResModifiedTime;
        try {
            patchedResModifiedTime = new File(patchedResPath).lastModified();
        } catch (Throwable thr) {
            patchedResModifiedTime = 0L;
        }
        if (patchedResModifiedTime == 0) {
            return false;
        }
        if (patchedResModifiedTime == storedPatchedResModifiedTime) {
            return false;
        }
        return true;
    }

    private static void recordCurrentPatchedResModifiedTime(String patchedResPath) {
        try {
            storedPatchedResModifiedTime = new File(patchedResPath).lastModified();
        } catch (Throwable thr) {
            storedPatchedResModifiedTime = 0L;
        }
    }

    private static void clearPreloadTypedArrayIssue(Resources resources) {
        try {
            final Field typedArrayPoolField = ShareReflectUtil.findField(Resources.class, "mTypedArrayPool");
            final Object origTypedArrayPool = typedArrayPoolField.get(resources);
            final Method acquireMethod = ShareReflectUtil.findMethod(origTypedArrayPool, "acquire");
            while (true) {
                if (acquireMethod.invoke(origTypedArrayPool) == null) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldAddSharedLibraryAssets(ApplicationInfo applicationInfo) {
        return SDK_INT >= Build.VERSION_CODES.N && applicationInfo != null &&
                applicationInfo.sharedLibraryFiles != null;
    }
}
