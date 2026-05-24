package com.bytedance.demo;

import android.app.Application;
import android.util.Log;

import com.bytedance.raphael.Raphael;

import java.io.File;

public class DemoApp extends Application {
    private static final String TAG = "DemoApp";

    @Override
    public void onCreate() {
        super.onCreate();
        File dir = new File(getExternalFilesDir(null), "raphael");
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
            }
        }
        String space = dir.getAbsolutePath();
        Log.d(TAG, "raphael output dir: " + space);
        Raphael.start(Raphael.MAP64_MODE | Raphael.ALLOC_MODE | 0x0F0000 | 1024, space, null);
//      Raphael.start(Raphael.MAP64_MODE|Raphael.ALLOC_MODE|0x0F0000|1024, space, ".*libhwui\\.so$");
    }
}