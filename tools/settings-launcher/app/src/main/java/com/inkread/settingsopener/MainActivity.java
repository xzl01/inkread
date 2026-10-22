package com.inkread.settingsopener;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「打开设置」——用于没有原生设置入口的 Android 墨水屏阅读器。
 *
 * 功能：
 * 1. 一键尝试打开 系统设置 / 开发者选项 / 关于设备 等标准设置页（隐式 Intent）；
 * 2. 扫描设备上所有类 Settings 的应用（厂商可能改了包名），列出其全部 Activity，
 *    点击即可尝试打开（用于 Settings 入口被厂商隐藏的情况）；
 * 3. 显示当前 开发者选项 / ADB 调试 的开关状态；
 * 4. 实验性地尝试直接写入 adb_enabled（需要 WRITE_SECURE_SETTINGS，普通安装会失败，
 *    仅用于确认权限状态）。
 *
 * 本工具不联网、不申请任何敏感权限、不收集任何数据。
 */
public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        addTitle("打开设置 / 开发者选项");
        addHint("适用于没有原生设置入口的墨水屏阅读器。设备信息与开关状态如下：");

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(8), 0, dp(12));
        root.addView(statusView);

        addSection("① 直接打开标准设置页");
        addButton("打开系统设置（Settings）", () -> launchIntent(new Intent(Settings.ACTION_SETTINGS)));
        addButton("打开开发者选项（Developer options）",
                () -> launchIntent(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        addButton("打开「无线调试」页面（Android 11+，配对码无线 adb 用）",
                this::tryWirelessDebuggingActivity);
        addButton("打开「关于设备 / 关于平板」（连点版本号入口）",
                () -> launchIntent(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        addButton("打开应用列表（Apps）", () -> launchIntent(new Intent(Settings.ACTION_APPLICATION_SETTINGS)));
        addButton("打开无障碍设置（Accessibility）",
                () -> launchIntent(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton("打开 Wi-Fi / 网络设置", () -> launchIntent(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addButton("打开显示设置", () -> launchIntent(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        addButton("打开本应用的应用信息页", () -> launchIntent(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));

        addSection("② 尝试已知的「开发者选项」类名（不同 ROM 类名不同）");
        addButton("逐个尝试开发者选项 Activity", this::tryKnownDevelopmentActivities);

        addSection("③ 扫描所有 Settings 类应用的 Activity 列表");
        addHint("如果上面的标准页面打不开，说明厂商改了设置包的包名或类名；点下面的按钮把设备上所有"
                + "含 settings 的应用的全部页面列出来，逐个点开找「开发者选项 / 关于本机」。");
        addButton("扫描 Settings 相关页面", this::scanSettingsActivities);

        addSection("④ ADB 状态");
        addButton("复制设备信息到剪贴板（发给协助者）", this::copyDeviceInfo);
        addButton("刷新状态", this::refreshStatus);
        addButton("实验：尝试直接打开 adb_enabled（通常需要系统权限）", this::tryForceEnableAdb);

        addHint("提示：USB 调试打开后，把设备连到电脑，在电脑终端执行 adb devices，"
                + "设备上弹出「允许 USB 调试吗？」时点允许，即可用 adb install 安装 App。");

        addSection("日志");
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        refreshStatus();
    }

    // ---------------------------------------------------------------- UI helpers

    private void addTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(20f);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(0, dp(4), 0, dp(8));
        root.addView(tv);
    }

    private void addSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setPadding(0, dp(18), 0, dp(6));
        root.addView(tv);
    }

    private void addHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setLineSpacing(0f, 1.25f);
        root.addView(tv);
    }

    private void addButton(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setOnClickListener(v -> {
            log("▶ " + text);
            try {
                action.run();
            } catch (Throwable t) {
                log("  ✗ 异常：" + t);
            }
        });
        root.addView(b);
    }

    private void log(String line) {
        if (logView == null) return;
        logView.append(line + "\n");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("厂商：").append(Build.MANUFACTURER).append('\n');
        sb.append("型号：").append(Build.MODEL).append('\n');
        sb.append("Android：").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("内核：").append(System.getProperty("os.version", "?")).append('\n');
        sb.append("指纹：").append(Build.FINGERPRINT).append('\n');
        sb.append("安全补丁：").append(Build.VERSION.SECURITY_PATCH).append('\n');
        sb.append("ABI：").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("SoC：").append(Build.SOC_MANUFACTURER).append(" ").append(Build.SOC_MODEL).append('\n');
        } else {
            sb.append("Board：").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        }
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            sb.append("内存：约 ").append(Math.round(mi.totalMem / 1024.0 / 1024 / 1024)).append(" GB\n");
            StatFs sf = new StatFs(getFilesDir().getAbsolutePath());
            sb.append("可用存储：约 ").append(sf.getAvailableBytes() / 1024 / 1024 / 1024).append(" GB\n");
        } catch (Throwable ignored) {
        }
        sb.append("开发者选项开关：").append(intGlobal(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) != 0 ? "已开启" : "未开启/未知").append('\n');
        sb.append("ADB 调试开关：").append(intGlobal(Settings.Global.ADB_ENABLED) != 0 ? "已开启" : "未开启/未知").append('\n');
        sb.append("无线调试开关：").append(intGlobal("adb_wifi_enabled") != 0 ? "已开启" : "未开启/未知").append('\n');
        List<String> pkgs = settingsPackages();
        sb.append("找到的设置类应用：").append(pkgs.isEmpty() ? "无" : TextUtils.join("、", pkgs));
        statusView.setText(sb.toString());
    }

    private int intGlobal(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- launch helpers

    private void launchIntent(Intent intent) {
        try {
            startActivity(intent);
            log("  ✓ 已发起：" + intent.getAction());
        } catch (Throwable t) {
            log("  ✗ 打不开（" + intent.getAction() + "）：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    private void launchComponent(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            log("  ✓ 已打开：" + pkg + "/" + cls);
        } catch (Throwable t) {
            log("  ✗ 打不开：" + pkg + "/" + cls + "  " + t.getClass().getSimpleName());
        }
    }

    private void tryKnownDevelopmentActivities() {
        String[][] candidates = {
                {"com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"},
                {"com.android.settings", "com.android.settings.development.DevelopmentSettingsDashboardActivity"},
                {"com.android.settings", "com.android.settings.development.DevelopmentSettingsActivity"},
                {"com.android.settings", "com.android.settings.Settings$DevelopmentSettingsActivity"},
                {"com.android.settings", "com.android.settings.DevelopmentSettingsActivity"},
                {"com.android.settings", "com.android.settings.development.DevelopmentSettingsDashboardFragment"},
        };
        PackageManager pm = getPackageManager();
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pm.resolveActivity(i, 0) == null) {
                    log("  · 不存在：" + c[1]);
                    continue;
                }
                startActivity(i);
                log("  ✓ 已打开：" + c[1]);
                return;
            } catch (Throwable t) {
                log("  ✗ " + c[1] + "：" + t.getClass().getSimpleName());
            }
        }
        log("  ! 以上类名都不存在，请用下面的「扫描 Settings 相关页面」逐个尝试。");
    }

    private void tryWirelessDebuggingActivity() {
        String[][] candidates = {
                {"com.android.settings", "com.android.settings.wifi.debugging.WirelessDebuggingActivity"},
                {"com.android.settings", "com.android.settings.development.WirelessDebuggingActivity"},
                {"com.android.settings", "com.android.settings.wifi.debugging.WirelessDebuggingFragment"},
                {"com.android.settings", "com.android.settings.Settings$WirelessDebuggingActivity"},
        };
        PackageManager pm = getPackageManager();
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pm.resolveActivity(i, 0) == null) {
                    log("  · 不存在：" + c[1]);
                    continue;
                }
                startActivity(i);
                log("  ✓ 已打开：" + c[1]);
                return;
            } catch (Throwable t) {
                log("  ✗ " + c[1] + "：" + t.getClass().getSimpleName());
            }
        }
        log("  ! 没找到无线调试页面。请先「打开开发者选项」，再在列表里找「无线调试」。");
    }

    // ---------------------------------------------------------------- scanning

    private List<String> settingsPackages() {
        List<String> result = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                String name = info.packageName;
                if (name != null && name.toLowerCase(Locale.ROOT).contains("settings")) {
                    result.add(name);
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(result);
        return result;
    }

    private void scanSettingsActivities() {
        List<String> pkgs = settingsPackages();
        // 也把能响应「打开设置」Intent 的包收进来
        try {
            Intent probe = new Intent(Settings.ACTION_SETTINGS);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(probe, 0)) {
                String p = ri.activityInfo.packageName;
                if (!pkgs.contains(p)) pkgs.add(p);
            }
        } catch (Throwable ignored) {
        }
        if (pkgs.isEmpty()) {
            log("  ! 没有找到任何设置类应用（可能被完全移除）");
            return;
        }

        int total = 0;
        for (String pkg : pkgs) {
            List<String> activities = activitiesOf(pkg);
            log("  包 " + pkg + " 共 " + activities.size() + " 个页面");
            for (String cls : activities) {
                if (total >= 150) {
                    log("  · 已列出 150 项，停止");
                    return;
                }
                total++;
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setTextSize(11f);
                b.setText(shorten(pkg, cls));
                b.setOnClickListener(v -> {
                    log("▶ " + cls);
                    launchComponent(pkg, cls);
                });
                root.addView(b);
            }
        }
        if (total == 0) log("  ! 这些应用没有可启动的 Activity");
    }

    private List<String> activitiesOf(String pkg) {
        List<String> result = new ArrayList<>();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (info.activities == null) return result;
            for (ActivityInfo ai : info.activities) {
                if (ai.exported && ai.name != null
                        && !ai.name.contains("Alias") && !ai.name.contains("$")) {
                    result.add(ai.name);
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(result);
        return result;
    }

    private String shorten(String pkg, String cls) {
        String name = cls.startsWith(pkg) ? cls.substring(pkg.length()) : cls;
        if (name.startsWith(".")) name = name.substring(1);
        name = name.replace("com.android.settings", "").replace("settings$", "");
        return name.isEmpty() ? cls : name;
    }

    private void copyDeviceInfo() {
        String text = statusView.getText().toString();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("device-info", text));
            log("  ✓ 已复制设备信息到剪贴板");
        } catch (Throwable t) {
            log("  ✗ 复制失败：" + t);
        }
    }

    // ---------------------------------------------------------------- adb

    private void tryForceEnableAdb() {
        try {
            Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            log("  · 写入完成，当前 adb_enabled=" + intGlobal(Settings.Global.ADB_ENABLED));
        } catch (Throwable t) {
            log("  ✗ 无法直接开启（普通应用没有 WRITE_SECURE_SETTINGS 权限）："
                    + t.getClass().getSimpleName());
            log("    请在开发者选项里手动勾选「USB 调试」；或打开「关于设备」连点版本号 7 次。");
        }
        refreshStatus();
    }
}
