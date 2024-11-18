package com.sandriver.loader;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtils {
    /**
     * @param context
     * @param zipFilePath 不带后缀
     * @return
     */
    public static String unzipLibFile(Context context, String zipFilePath) {
        try {
            File zipFile = new File(zipFilePath);
            File unZipFileDir = new File(context.getFilesDir(), zipFile.getName() + "res");
            // 如果目标目录不存在，则创建
            if (unZipFileDir.exists()) {
                FileUtils.deleteDirectoryOrFile(unZipFileDir.getAbsolutePath());
            }
            unZipFileDir.mkdirs();
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFile));
            if (unzipFromAssets(zipInputStream, unZipFileDir)) {
                return new File(unZipFileDir, "lib").getAbsolutePath();
            }
        } catch (Exception e) {

        }
        return "";
    }

    private void checkZipSafe(String untrustedFileName, String DIR) {

        try {
            FileInputStream is = new FileInputStream(untrustedFileName);
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is));
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                File f = new File(DIR, ze.getName());
                String canonicalPath = f.getCanonicalPath();
                if (!canonicalPath.startsWith(DIR)) {
                    // SecurityException
                }
                // Finish unzipping…
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {

        }

    }

    private void ensureZipPathSafety(final File outputFile, final String destDirectory) throws Exception {
        String destDirCanonicalPath = (new File(destDirectory)).getCanonicalPath();
        String outputFileCanonicalPath = outputFile.getCanonicalPath();
        if (!outputFileCanonicalPath.startsWith(destDirCanonicalPath)) {
            throw new Exception(String.format("Found Zip Path Traversal Vulnerability with %s", outputFileCanonicalPath));
        }
    }

    public static boolean unzipFileFromAssets(Context context, String zipFileName) {
        try {
            File unZipFileDir = new File(context.getFilesDir(), zipFileName);
            // 如果目标目录不存在，则创建
            if (unZipFileDir.exists()) {
                FileUtils.deleteDirectoryOrFile(unZipFileDir.getAbsolutePath());
            }
            unZipFileDir.mkdirs();

            // 打开压缩文件
            InputStream inputStream = context.getAssets().open(zipFileName);
            ZipInputStream zipInputStream = new ZipInputStream(inputStream);
            return unzipFromAssets(zipInputStream, unZipFileDir);
        } catch (Exception e) {

        }
        return false;
    }

    public static boolean unzipFromAssets(ZipInputStream zipInputStream, File unZipFileDir) {
        try {
            // 读取一个进入点
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            // 使用1Mbuffer
            byte[] buffer = new byte[1024 * 1024];
            // 解压时字节计数
            int count = 0;
            // 如果进入点为空说明已经遍历完所有压缩包中文件和目录
            while (zipEntry != null) {
                if (zipEntry.getName().startsWith("lib/")) {
                    if (!zipEntry.isDirectory()) {  //如果是一个文件
                        File unZipFile = new File(unZipFileDir, zipEntry.getName());
                        if (unZipFile.exists()) {
                            unZipFile.delete();
                        }
                        unZipFile.getParentFile().mkdirs();
                        unZipFile.createNewFile();
                        FileOutputStream fileOutputStream = new FileOutputStream(unZipFile);
                        while ((count = zipInputStream.read(buffer)) > 0) {
                            fileOutputStream.write(buffer, 0, count);
                        }
                        fileOutputStream.close();

                    }
                }
                zipEntry = zipInputStream.getNextEntry();
            }
            zipInputStream.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String copyAssetFile(Context context, String fileName, File targetRootFile) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = context.getAssets().open(fileName);
            if (!targetRootFile.exists()) {
                targetRootFile.mkdirs();
            }
            os = new FileOutputStream(new File(targetRootFile, fileName));
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null)
                    is.close();
                if (os != null)
                    os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return targetRootFile.getAbsolutePath();
    }
}