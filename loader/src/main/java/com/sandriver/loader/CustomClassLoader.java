package com.sandriver.loader;

import android.annotation.SuppressLint;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;

import dalvik.system.PathClassLoader;

@SuppressLint("NewApi")
public final class CustomClassLoader extends PathClassLoader {
    private final ClassLoader mOriginAppClassLoader;

    CustomClassLoader(String dexPath, File optimizedDir, String libraryPath, ClassLoader originAppClassLoader) {
        super("", libraryPath, ClassLoader.getSystemClassLoader());
        mOriginAppClassLoader = originAppClassLoader;
        injectDexPath(this, dexPath, optimizedDir);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> cl = null;
        try {
            cl = super.findClass(name);
        } catch (ClassNotFoundException ignored) {
            cl = null;
        }
        if (cl != null) {
            return cl;
        } else {
            return mOriginAppClassLoader.loadClass(name);
        }
    }

    @Override
    public URL getResource(String name) {
        // The lookup order we use here is the same as for classes.
        URL resource = Object.class.getClassLoader().getResource(name);
        if (resource != null) {
            return resource;
        }

        resource = findResource(name);
        if (resource != null) {
            return resource;
        }

        return mOriginAppClassLoader.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        @SuppressWarnings("unchecked") final Enumeration<URL>[] resources = (Enumeration<URL>[]) new Enumeration<?>[]{
                Object.class.getClassLoader().getResources(name),
                findResources(name),
                mOriginAppClassLoader.getResources(name)
        };
        return new CompoundEnumeration<>(resources);
    }

    private static void injectDexPath(ClassLoader cl, String dexPath, File optimizedDir) {
        try {
            final List<File> dexFiles = new ArrayList<>(16);
            for (String oneDexPath : dexPath.split(":")) {
                if (oneDexPath.isEmpty()) {
                    continue;
                }
                dexFiles.add(new File(oneDexPath));
            }
            if (!dexFiles.isEmpty()) {
                Field pathListField = ShareReflectUtil.findField(cl, "pathList");
                Object dexPathList = pathListField.get(cl);
                ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();

                Object[] dexArr = makePathElements(dexPathList,
                        new ArrayList<File>(dexFiles), optimizedDir,
                        suppressedExceptions);
                ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", dexArr);
                if (suppressedExceptions.size() > 0) {
                    for (IOException e : suppressedExceptions) {
                        throw e;
                    }

                }
            }
        } catch (Throwable thr) {
        }
    }

    private static Object[] makePathElements(
            Object dexPathList, ArrayList<File> files, File optimizedDirectory,
            ArrayList<IOException> suppressedExceptions)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        Method makePathElements;
        try {
            makePathElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", List.class, File.class,
                    List.class);
        } catch (NoSuchMethodException e) {
            try {
                makePathElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", ArrayList.class, File.class, ArrayList.class);
            } catch (NoSuchMethodException e1) {
                throw e1;
            }
        }

        return (Object[]) makePathElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
    }

    class CompoundEnumeration<E> implements Enumeration<E> {
        private Enumeration<E>[] enums;
        private int index = 0;

        public CompoundEnumeration(Enumeration<E>[] enums) {
            this.enums = enums;
        }

        @Override
        public boolean hasMoreElements() {
            while (index < enums.length) {
                if (enums[index] != null && enums[index].hasMoreElements()) {
                    return true;
                }
                index++;
            }
            return false;
        }

        @Override
        public E nextElement() {
            if (!hasMoreElements()) {
                throw new NoSuchElementException();
            }
            return enums[index].nextElement();
        }
    }

}