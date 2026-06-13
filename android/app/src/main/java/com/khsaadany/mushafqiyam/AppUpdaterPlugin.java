package com.khsaadany.mushafqiyam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import android.Manifest;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(
    name = "AppUpdater",
    permissions = {
        @Permission(
            alias = "microphone",
            strings = { Manifest.permission.RECORD_AUDIO }
        )
    }
)
public class AppUpdaterPlugin extends Plugin {

    @PluginMethod
    public void getAppVersion(PluginCall call) {
        try {
            Context context = getContext();
            String versionName = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
            JSObject ret = new JSObject();
            ret.put("version", versionName);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to get app version: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void installApk(PluginCall call) {
        String apkUrl = call.getString("url");
        if (apkUrl == null || apkUrl.isEmpty()) {
            call.reject("URL is required");
            return;
        }

        final Context context = getContext();
        
        // Execute download and install in a background thread to prevent UI freezing
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Download APK to Cache Directory
                    URL url = new URL(apkUrl);
                    HttpURLConnection connection = null;
                    int redirectCount = 0;
                    int maxRedirects = 5;

                    while (true) {
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Mobile Safari/537.36");
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout(20000);
                        connection.setInstanceFollowRedirects(true);

                        int status = connection.getResponseCode();
                        if (status == HttpURLConnection.HTTP_MOVED_TEMP 
                            || status == HttpURLConnection.HTTP_MOVED_PERM 
                            || status == HttpURLConnection.HTTP_SEE_OTHER 
                            || status == 307 
                            || status == 308) {
                            
                            redirectCount++;
                            if (redirectCount > maxRedirects) {
                                call.reject("Too many redirects");
                                return;
                            }
                            String newUrl = connection.getHeaderField("Location");
                            if (newUrl == null || newUrl.isEmpty()) {
                                call.reject("Redirect Location is empty");
                                return;
                            }
                            connection.disconnect();
                            url = new URL(url, newUrl);
                        } else {
                            break;
                        }
                    }

                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        call.reject("Server returned HTTP " + connection.getResponseCode());
                        return;
                    }

                    File cacheDir = context.getCacheDir();
                    File apkFile = new File(cacheDir, "update.apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    try (InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(apkFile)) {
                        byte[] data = new byte[4096];
                        int count;
                        while ((count = input.read(data)) != -1) {
                            output.write(data, 0, count);
                        }
                    }

                    // 2. Launch Android Package Installer
                    Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    
                    context.startActivity(intent);

                    // Resolve the call back to JS
                    JSObject ret = new JSObject();
                    ret.put("status", "success");
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Failed to download or install APK: " + e.getMessage(), e);
                }
            }
        }).start();
    }
}
