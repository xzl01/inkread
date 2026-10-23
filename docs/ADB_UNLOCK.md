# 讯飞 XF-R3（起点阅读器）ADB 解锁说明

设备：讯飞起点阅读器（iFlytek EBook R3 / XF-R3），Android 11，userdebug 构建，SELinux 为 Permissive。

## 问题

厂商定制过的 adbd 会拒绝标准服务：`adb shell` / `adb root` / `adb install` 全部报
`error: not support command ...`，即使开发者选项里显示「ADB 调试已开启」。

只有把运行期属性 **`service.iflytek.usb.allowed` 设为 `true`**（再配合
`sys.rkadb.root=1`、`service.adb.root=1`）后，adb 才会完全放开。

## 方案一（推荐，永久生效、不改系统分区）

`tools/settings-launcher`（「打开设置」小工具）内置了一个 `BootUnlockReceiver`：
开机收到 `BOOT_COMPLETED` 时执行

```sh
setprop service.iflytek.usb.allowed true
setprop sys.rkadb.root 1
setprop service.adb.root 1
```

设备 SELinux 是 Permissive，普通应用通过 `setprop` 就能写这些属性。

使用：

1. 安装 `dist/settings-opener.apk`（或从源码构建 `tools/settings-launcher`）；
2. **手动打开一次**该应用（Android 的 stopped 状态规则：新装的 app 未启动过不会收到开机广播）；
3. 之后每次开机，adb 自动是可用的 root shell：

```bash
adb shell id     # uid=0(root)
adb install -r xxx.apk
```

卸载该应用即完全恢复原状（属性是运行期的，不写任何分区）。

> 注意：这台设备 `adb reboot` 后需要**按一下电源键**才会重新开机（不会自动上电）。

## 方案二（不装 app，用 JDWP 临时解锁）

设备是 userdebug，`ro.debuggable=1`，system_server 和大量系统应用可被 JDWP 附加，
可以在 system UID 进程里直接调用 `android.os.SystemProperties.set`：

```bash
bash .tooling/scripts/adb-unlock.sh
```

脚本会：

1. 扫描 `adb jdwp`，找到 uid=1000 的进程（system_server）；
2. 通过 JDI 附加后调用 `SystemProperties.set` 打开上面三个属性；
3. 执行 `adb root` 并验证。

适合不想安装常驻 app、或需要临时调试的场景。

## 关于 EinkAMService（熄屏禁止第三方应用启动）

厂商把 `com.android.server.am.EinkActivityManagerService` 编进了
`/system/framework/iservices.jar`（system_server 进程内，不是独立 App）。
它会在**屏幕熄灭时**拦截不在白名单里的第三方应用启动：

```
EinkAMService: Not allowed to start. type=1, componentName=...
```

- 屏幕点亮时启动第三方应用不受影响；
- 临时关闭拦截：`.tooling/scripts/adb-unlock.sh` 会在解锁 adb 后把
  `EinkActivityManagerService.mBootCompleted` 置为 `false`（运行时补丁，重启失效）；
- 永久关闭需要改 `/system/framework/framework-res.apk` 的白名单数组
  `config_background_white_apps` 或 `iservices.jar`，属于系统分区修改，
  有卡开机/OTA 风险，本仓库**没有**做这一步。

## 备份

分区备份（loader 模式 dump）：`.tooling/device-dumps/`（boot、vbmeta、dtbo、uboot、recovery）。
super 分区全量备份：`.tooling/device-backup/super.img`（3,263,168,512 字节）。
这些是本地调试资料，不入库。
