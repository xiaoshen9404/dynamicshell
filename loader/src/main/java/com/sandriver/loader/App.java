package com.sandriver.loader;

import android.app.Application;
import android.content.ContentProvider;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class App extends Application {

    public static App currentApp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        currentApp = App.this;
        onCreateAtt();
    }

    private void attachApp(String unzipPath, String libPath) {
        try {
            File unzipFile = new File(unzipPath);
            doInject(this, (ClassLoader) createNewClassLoader(getClassLoader(), unzipFile, unzipFile.getAbsolutePath()));
            File abiDir = new File(libPath);
            if (Objects.requireNonNull(abiDir.listFiles()).length > 0) {
                String abiName = getPluginPreferredAbi(
                        Build.SUPPORTED_ABIS,
                        abiDir
                );
                if (!TextUtils.isEmpty(abiName)) {
                    installNativeLibraryPath(getClassLoader(), new File(libPath, abiName));
                }
            }
        } catch (Throwable e) {

        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        onCreateApp();
    }

    protected void onCreateAtt() {
        try {
            SharedPreferences sharedPreferences = getSharedPreferences("sp_config", Context.MODE_PRIVATE);
            int currentVersion = getVersionCode(this);
            int version = sharedPreferences.getInt("bundle_version", 0);
            if (currentVersion != version) {
                File app = new File(getFilesDir(), BundleConfigs.BUNDLE_ASSETS_FILE_NAME);
                if (app.exists()) {
                    app.delete();
                }
//                app.createNewFile();

                ZipUtils.copyAssetFile(this, BundleConfigs.BUNDLE_ASSETS_FILE_NAME, getFilesDir());

                File unzipFile = new File(getFilesDir(), BundleConfigs.BUNDLE_ASSETS_FILE_NAME);
                String libPath = ZipUtils.unzipLibFile(this, unzipFile.getAbsolutePath());
                attachApp(unzipFile.getAbsolutePath(), libPath);

                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("sp_config", currentVersion);
                editor.putString("libPath", libPath);
                editor.commit();
            } else {
                File unzipFile = new File(getFilesDir(), BundleConfigs.BUNDLE_ASSETS_FILE_NAME);
                String oldLibDirPath = sharedPreferences.getString("libPath", "");
                attachApp(unzipFile.getAbsolutePath(), oldLibDirPath);
            }
        } catch (Exception e) {
        }
    }

    protected void onCreateApp() {
        try {
            File unzipFile = new File(getFilesDir(), BundleConfigs.BUNDLE_ASSETS_FILE_NAME);
            setDelegateApplication();
            ResourcePatcher.isResourceCanPatch();
            ResourcePatcher.monkeyPatchExistingResources(unzipFile.getAbsolutePath(), false);
        } catch (Throwable e) {

        }
    }

    public int getVersionCode(Context context) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo;
        int versionCode = 0;
        try {
            packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            versionCode = packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return versionCode;
    }

    public static String getPluginPreferredAbi(String[] pluginSupportedAbis, File libDir) {
        Set<String> subDirsInLib = new LinkedHashSet<>();

        for (File file : libDir.listFiles()) {
            subDirsInLib.add(file.getName());
        }

        for (String supportedAbi : pluginSupportedAbis) {
            if (subDirsInLib.contains(supportedAbi)) {
                return supportedAbi;
            }
        }
        return "";
    }

    private static Object createNewClassLoader(ClassLoader oldClassLoader,
                                               File dexOptDir,
                                               String... patchDexPaths) throws Throwable {
        final Field pathListField = findField(
                Class.forName("dalvik.system.BaseDexClassLoader", false, oldClassLoader),
                "pathList");
        final Object oldPathList = pathListField.get(oldClassLoader);

        final StringBuilder dexPathBuilder = new StringBuilder();
        final boolean hasPatchDexPaths = patchDexPaths != null && patchDexPaths.length > 0;
        if (hasPatchDexPaths) {
            for (int i = 0; i < patchDexPaths.length; ++i) {
                if (i > 0) {
                    dexPathBuilder.append(File.pathSeparator);
                }
                dexPathBuilder.append(patchDexPaths[i]);
            }
        }

        final String combinedDexPath = dexPathBuilder.toString();

        ClassLoader result = new CustomClassLoader(combinedDexPath, dexOptDir, "", oldClassLoader);

        if (!Utils.isNewerOrEqualThanVersion(26)) {
            findField(oldPathList.getClass(), "definingContext").set(oldPathList, result);
        }

        return result;
    }

    public static void doInject(Application app, ClassLoader classLoader) throws Throwable {
        Thread.currentThread().setContextClassLoader(classLoader);

        final Context baseContext = (Context) findField(app.getClass(), "mBase").get(app);
        try {
            findField(baseContext.getClass(), "mClassLoader").set(baseContext, classLoader);
        } catch (Throwable ignored) {
        }

        final Object basePackageInfo = findField(baseContext.getClass(), "mPackageInfo").get(baseContext);
        findField(basePackageInfo.getClass(), "mClassLoader").set(basePackageInfo, classLoader);

        if (Build.VERSION.SDK_INT < 27) {
            final Resources res = app.getResources();
            try {
                findField(res.getClass(), "mClassLoader").set(res, classLoader);

                final Object drawableInflater = findField(res.getClass(), "mDrawableInflater").get(res);
                if (drawableInflater != null) {
                    findField(drawableInflater.getClass(), "mClassLoader").set(drawableInflater, classLoader);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void installNativeLibraryPath(ClassLoader classLoader, File folder)
            throws Throwable {
        if (folder == null || !folder.exists()) {
            return;
        }
        if ((Build.VERSION.SDK_INT == 25 && Build.VERSION.PREVIEW_SDK_INT != 0)
                || Build.VERSION.SDK_INT > 25) {
            final Field pathListField = ShareReflectUtil.findField(classLoader, "pathList");
            final Object dexPathList = pathListField.get(classLoader);

            final Field nativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "nativeLibraryDirectories");

            List<File> origLibDirs = (List<File>) nativeLibraryDirectories.get(dexPathList);
            if (origLibDirs == null) {
                origLibDirs = new ArrayList<>(2);
            }
            final Iterator<File> libDirIt = origLibDirs.iterator();
            while (libDirIt.hasNext()) {
                final File libDir = libDirIt.next();
                if (folder.equals(libDir)) {
                    libDirIt.remove();
                    break;
                }
            }
            origLibDirs.add(0, folder);

            final Field systemNativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "systemNativeLibraryDirectories");
            List<File> origSystemLibDirs = (List<File>) systemNativeLibraryDirectories.get(dexPathList);
            if (origSystemLibDirs == null) {
                origSystemLibDirs = new ArrayList<>(2);
            }

            final List<File> newLibDirs = new ArrayList<>(origLibDirs.size() + origSystemLibDirs.size() + 1);
            newLibDirs.addAll(origLibDirs);
            newLibDirs.addAll(origSystemLibDirs);

            final Method makeElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", List.class);

            final Object[] elements = (Object[]) makeElements.invoke(dexPathList, newLibDirs);

            final Field nativeLibraryPathElements = ShareReflectUtil.findField(dexPathList, "nativeLibraryPathElements");
            nativeLibraryPathElements.set(dexPathList, elements);
        } else {
            final Field pathListField = ShareReflectUtil.findField(classLoader, "pathList");
            final Object dexPathList = pathListField.get(classLoader);

            final Field nativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "nativeLibraryDirectories");

            List<File> origLibDirs = (List<File>) nativeLibraryDirectories.get(dexPathList);
            if (origLibDirs == null) {
                origLibDirs = new ArrayList<>(2);
            }
            final Iterator<File> libDirIt = origLibDirs.iterator();
            while (libDirIt.hasNext()) {
                final File libDir = libDirIt.next();
                if (folder.equals(libDir)) {
                    libDirIt.remove();
                    break;
                }
            }
            origLibDirs.add(0, folder);

            final Field systemNativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "systemNativeLibraryDirectories");
            List<File> origSystemLibDirs = (List<File>) systemNativeLibraryDirectories.get(dexPathList);
            if (origSystemLibDirs == null) {
                origSystemLibDirs = new ArrayList<>(2);
            }

            final List<File> newLibDirs = new ArrayList<>(origLibDirs.size() + origSystemLibDirs.size() + 1);
            newLibDirs.addAll(origLibDirs);
            newLibDirs.addAll(origSystemLibDirs);

            final Method makeElements = ShareReflectUtil.findMethod(dexPathList,
                    "makePathElements", List.class, File.class, List.class);
            final ArrayList<IOException> suppressedExceptions = new ArrayList<>();

            final Object[] elements = (Object[]) makeElements.invoke(dexPathList, newLibDirs, null, suppressedExceptions);

            final Field nativeLibraryPathElements = ShareReflectUtil.findField(dexPathList, "nativeLibraryPathElements");
            nativeLibraryPathElements.set(dexPathList, elements);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws Throwable {
        Class<?> currClazz = clazz;
        while (true) {
            try {
                final Field result = currClazz.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (Throwable ignored) {
                if (currClazz == Object.class) {
                    throw new NoSuchFieldException("Cannot find field "
                            + name + " in class " + clazz.getName() + " and its super classes.");
                } else {
                    currClazz = currClazz.getSuperclass();
                }
            }
        }
    }

    public static Application setDelegateApplication() throws PackageManager.NameNotFoundException {
        ApplicationInfo info = currentApp.getPackageManager().getApplicationInfo(currentApp.getPackageName(), PackageManager.GET_META_DATA);
        Application delegateApplication = null;
        try {
            // 先获取到ContextImpl对象
            Context contextImpl = currentApp.getBaseContext();
            // 创建插件中真实的Application且，执行生命周期
            ClassLoader classLoader = currentApp.getClassLoader();
            Class<?> applicationClass = classLoader.loadClass(BundleConfigs.ORIGIAN_APPLICATION_CLASS_NAME);
            delegateApplication = (Application) applicationClass.newInstance();
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            attachMethod.setAccessible(true);
            attachMethod.invoke(delegateApplication, contextImpl);

            // 替换ContextImpl的代理Application
            Class contextImplClass = contextImpl.getClass();
            Method setOuterContextMethod = contextImplClass.getDeclaredMethod("setOuterContext", Context.class);
            setOuterContextMethod.setAccessible(true);
            setOuterContextMethod.invoke(contextImpl, delegateApplication);
            // 替换LoadedApk的代理Application
            Field loadedApkField = contextImplClass.getDeclaredField("mPackageInfo");
            loadedApkField.setAccessible(true);
            Object loadedApk = loadedApkField.get(contextImpl);
            Class<?> loadedApkClass = Class.forName("android.app.LoadedApk");
            Field mApplicationField = loadedApkClass.getDeclaredField("mApplication");
            mApplicationField.setAccessible(true);
            mApplicationField.set(loadedApk, delegateApplication);

            // 替换ActivityThread的代理Application
            Field mMainThreadField = contextImplClass.getDeclaredField("mMainThread");
            mMainThreadField.setAccessible(true);
            Object mMainThread = mMainThreadField.get(contextImpl);
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
            mInitialApplicationField.setAccessible(true);
            mInitialApplicationField.set(mMainThread, delegateApplication);
            Field mAllApplicationsField = activityThreadClass.getDeclaredField("mAllApplications");
            mAllApplicationsField.setAccessible(true);
            ArrayList<Application> mAllApplications = (ArrayList<Application>) mAllApplicationsField.get(mMainThread);
            mAllApplications.remove(currentApp);
            mAllApplications.add(delegateApplication);

            // 替换LoadedApk中的mApplicationInfo中name
            Field mApplicationInfoField = loadedApkClass.getDeclaredField("mApplicationInfo");
            mApplicationInfoField.setAccessible(true);
            ApplicationInfo applicationInfo = (ApplicationInfo) mApplicationInfoField.get(loadedApk);
            applicationInfo.className = BundleConfigs.ORIGIAN_APPLICATION_CLASS_NAME;
            delegateApplication.onCreate();
            replaceContentProvider(mMainThread, delegateApplication);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return delegateApplication != null ? delegateApplication : currentApp;
    }

    /**
     * 修改已经存在ContentProvider中application
     *
     * @param activityThread
     * @param delegateApplication
     */
    private static void replaceContentProvider(Object activityThread, Application delegateApplication) {
        try {
            Field mProviderMapField = activityThread.getClass().getDeclaredField("mProviderMap");
            mProviderMapField.setAccessible(true);
            Map<Object, Object> mProviderMap = (Map<Object, Object>) mProviderMapField.get(activityThread);
            Set<Map.Entry<Object, Object>> entrySet = mProviderMap.entrySet();
            for (Map.Entry<Object, Object> entry : entrySet) {
                // 取出ContentProvider
                Object providerClientRecord = entry.getValue();
                Field mLocalProviderField = providerClientRecord.getClass().getDeclaredField("mLocalProvider");
                mLocalProviderField.setAccessible(true);
                ContentProvider contentProvider = (ContentProvider) mLocalProviderField.get(providerClientRecord);
                if (contentProvider != null) {
                    // 修改ContentProvider中的context
                    Field contextField = Class.forName("android.content.ContentProvider").getDeclaredField("mContext");
                    contextField.setAccessible(true);
                    contextField.set(contentProvider, delegateApplication);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}