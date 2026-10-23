# JdwpAgent

一个很小的 JDWP（Java Debug Wire Protocol）客户端，用于在 **userdebug / 可调试** 的 Android
设备上，通过 `adb forward tcp:PORT jdwp:PID` 附加到目标进程，做这些事：

| 模式 | 作用 |
|---|---|
| `info <port>` | 打印目标进程的 pid / uid / package |
| `dump <port> <keyword>` | 搜索目标进程里类名/方法名包含关键字的成员（用于分析厂商 API） |
| `exec <port> <command>` | 在目标进程的 UID 下执行 shell 命令 |
| `setprop <port> <key> <value>` | 在目标进程里调用 `android.os.SystemProperties.set` |
| `setprop2 <port> <key> <value>` | 同上，但用 `vm.suspend()` 方式取线程（目标进程空闲时用） |
| `patchbool <port> <class> <field> <true\|false>` | 修改目标进程中某个实例/静态的 boolean 字段（运行时补丁） |
| `getbool <port> <class> <field>` | 读取 boolean 字段 |

## 构建

```bash
# 用仓库自带的 JDK 21（或任意 JDK 11+，需要 jdk.jdi 模块）
.tooling/jdk21/bin/javac --add-modules jdk.jdi -d tools/jdwp/out tools/jdwp/JdwpAgent.java
```

## 用法示例

```bash
# 找可附加的 pid（userdebug 设备上 system_server 也在列表里）
adb jdwp | sort -n

# 附加到 system_server，打开厂商隐藏的 ADB 开关
adb forward tcp:8702 jdwp:416
.tooling/jdk21/bin/java --add-modules jdk.jdi -cp tools/jdwp/out JdwpAgent \
    setprop 8702 service.iflytek.usb.allowed true

# 关闭 EinkAMService 的熄屏启动拦截（运行时，重启失效）
.tooling/jdk21/bin/java --add-modules jdk.jdi -cp tools/jdwp/out JdwpAgent \
    patchbool 8702 com.android.server.am.EinkActivityManagerService mBootCompleted false
```

封装好的一键脚本见 `.tooling/scripts/adb-unlock.sh`（本地调试脚本，不在仓库内）。
背景与设备细节见 [`docs/ADB_UNLOCK.md`](../../docs/ADB_UNLOCK.md)。
