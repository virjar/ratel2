package com.ratel.e2ehost;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** e2e 宿主：无任何 ratel 依赖——测试的是「原始 apk 进 ratel 管线」这一侧。 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("ratel e2e host");
        setContentView(tv);
        // 模拟 app 自校验：读自身 apk（sourceDir）算 crc——crcBypass 开启时应读到原始字节
        logSelfCrc();
        // 模拟资源系统读 axml（crcBypass 的坑场景：重定向后 zip 索引错位会在此 crash）
        readAxml();
    }

    private void readAxml() {
        try (android.content.res.XmlResourceParser parser =
                     getAssets().openXmlResourceParser("AndroidManifest.xml")) {
            int event = parser.next();
            int nodes = 0;
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && nodes < 10) {
                event = parser.next();
                nodes++;
            }
            Log.i("E2EHost", "axml-read=OK nodes>=" + nodes);
        } catch (Throwable t) {
            Log.i("E2EHost", "axml-read=CRASH " + t);
        }
    }

    private void logSelfCrc() {
        try {
            String apkPath = getApplicationInfo().sourceDir;
            CRC32 crc = new CRC32();
            try (ZipFile zip = new ZipFile(apkPath)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var e = entries.nextElement();
                    crc.update(e.getName().getBytes("UTF-8"));
                    crc.update((int) e.getCrc());
                    crc.update((int) e.getSize());
                }
            }
            Log.i("E2EHost", "self-crc=" + crc.getValue() + " path=" + apkPath);
        } catch (Throwable t) {
            Log.i("E2EHost", "self-crc=ERROR " + t);
        }
    }
}
