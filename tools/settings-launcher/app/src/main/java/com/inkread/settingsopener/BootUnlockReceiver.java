package com.inkread.settingsopener;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机时自动打开厂商隐藏的完整 ADB 开关。
 *
 * 这台讯飞 XF-R3 的 adbd 被厂商限制，只有在运行期属性
 * service.iflytek.usb.allowed=true（配合 sys.rkadb.root=1）时，
 * adb shell / adb root / adb install 才会完全放开。
 * 该属性重启会丢失，所以在 BOOT_COMPLETED 时重新设置一次。
 *
 * 说明：设备 SELinux 为 Permissive，普通应用通过 setprop 即可写这些属性；
 * 不改任何系统分区，卸载本应用即完全恢复原状。
 */
public class BootUnlockReceiver extends BroadcastReceiver {
    private static final String TAG = "InkAdbBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/sh", "-c",
                    "setprop service.iflytek.usb.allowed true; "
                            + "setprop sys.rkadb.root 1; "
                            + "setprop service.adb.root 1"
            });
            p.waitFor();
            Log.i(TAG, "vendor adb props applied");
        } catch (Throwable t) {
            Log.w(TAG, "apply failed: " + t);
        }
    }
}
