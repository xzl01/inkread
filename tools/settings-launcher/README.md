# 打开设置（Settings Opener）

一个 16 KB 的小工具 APK，用来在**没有原生设置入口**的 Android 设备（典型场景：国产电子墨水屏阅读器，
厂商 ROM 把 AOSP 的「设置」图标隐藏甚至移除了）上打开：

- 系统设置（`android.settings.SETTINGS`）
- 开发者选项（`android.settings.APPLICATION_DEVELOPMENT_SETTINGS`）
- 关于设备 / 应用列表 / 无障碍 / Wi-Fi / 显示设置
- 扫描设备上所有「Settings 类」应用的 Activity 列表，逐个尝试（厂商改了包名/类名时用）
- 显示当前 开发者选项 / ADB 调试 的开关状态
- 实验性地尝试直接写 `adb_enabled`（普通应用无 `WRITE_SECURE_SETTINGS`，通常会失败，仅用于确认权限）

> 不联网、不申请任何权限（仅声明 `RECEIVE_BOOT_COMPLETED`）、不收集数据。

## 开机自动解锁厂商 ADB（讯飞 XF-R3 等）

部分厂商设备（如讯飞起点阅读器 XF-R3）的 adbd 被定制限制：`adb shell` / `adb root` / `adb install`
全部报 `not support command`，只有运行期属性 `service.iflytek.usb.allowed=true` 时才放开。

本工具的 `BootUnlockReceiver` 会在开机收到 `BOOT_COMPLETED` 时自动执行：

```sh
setprop service.iflytek.usb.allowed true
setprop sys.rkadb.root 1
setprop service.adb.root 1
```

设备 SELinux 为 Permissive，因此普通应用即可设置这些属性；**不修改系统分区**，卸载即恢复原状。

使用：安装后**手动打开一次**本应用（解除 Android 的 stopped 状态），之后每次开机 adb 自动为 root 可用。

原理与更多细节见 [`docs/ADB_UNLOCK.md`](../../docs/ADB_UNLOCK.md)。

## 构建

```bash
# 在仓库根目录执行（使用仓库自带 JDK/Gradle 脚本；普通环境会自动回退到 ./gradlew）
bash scripts/dev-gradle.sh -p tools/settings-launcher :app:assembleRelease
# 产物：tools/settings-launcher/app/build/outputs/apk/release/app-release.apk
```

## 使用

1. 把 APK 拷到设备（USB/MTP、SD 卡、网盘均可），用设备自带的文件管理器点击安装；
2. 打开「打开设置」App：
   - 先看顶部状态：设备厂商/型号、开发者选项开关、ADB 开关、找到的设置类应用；
   - 依次点「打开系统设置」/「打开开发者选项」；
   - 都打不开就点「扫描 Settings 相关页面」，在列表里找 `DevelopmentSettings`、`DeviceInfoSettings`、
     `Settings` 等条目逐个尝试；
3. 进入「关于设备」后连续点击「版本号」7 次，即可解锁开发者选项，再打开「USB 调试」。

更完整的图文说明（含电脑端 adb 排查）见仓库 [`docs/DEVICE_SETTINGS.md`](../../docs/DEVICE_SETTINGS.md)。

## 说明

- `minSdk 21`，兼容 Android 5.0+（墨水屏设备常见 Android 8~13）；
- 使用 debug 签名，仅用于侧载；如对外分发请自行配置签名；
- 代码全部在 `app/src/main/java/com/inkread/settingsopener/MainActivity.java`，约 300 行，无第三方依赖。
