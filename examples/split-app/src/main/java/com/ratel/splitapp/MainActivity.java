package com.ratel.splitapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextSize(24f);
        // split 可见性自检：config.arm64_v8a split 的 so 是否加载
        String nativeLib;
        try {
            System.loadLibrary("nativemodule");
            nativeLib = "loaded";
        } catch (Throwable t) {
            nativeLib = "MISSING(" + t.getClass().getSimpleName() + ")";
        }
        Log.i("SplitApp", "nativeLib=" + nativeLib);
        tv.setText("nativeLib: " + nativeLib);
        setContentView(tv);
    }
}
