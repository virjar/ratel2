package com.ratel.runtime;

import android.app.AppComponentFactory;

/**
 * ACF 入口方案（virjar 三入口之二）：manifest appComponentFactory 指向本类，
 * 在 Application 创建之前拉起 ratel bootstrap。
 *
 * <p>原 app 有自定义 ACF 时通过 meta-data ratel_origin_acf 链式委托。</p>
 */
public class RatelAppComponentFactory extends AppComponentFactory {

    static {
        try {
            Bootstrap.bootstrap();
        } catch (Throwable t) {
            android.util.Log.e("RatelLoader", "ACF bootstrap failed: " + t);
        }
    }
}
