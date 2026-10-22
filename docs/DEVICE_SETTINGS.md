# 没有原生「设置」入口时，如何打开开发者选项 / USB 调试

> 适用场景：国产电子墨水屏阅读器（大我 Bigme / 掌阅 iReader / 汉王 / 墨案 / Meebook 等）的厂商 ROM
> 把 AOSP 的「设置」Launcher 图标隐藏或移除了，但通常**设置页面本身还在系统里**，只是没有入口。
>
> 本文同样适用于想用 `adb install` 安装 `dist/inkread-v0.1.0-release.apk` 的场景。

---

## 先说结论

1. **安装 App 本身不需要开发者模式**：把 APK 拷到设备上，用设备自带的「文件管理」点击安装即可
   （部分设备需先在厂商设置里允许「安装未知来源应用」）。
2. 开发者模式 / USB 调试只在需要 **adb**（安装、抓日志、调试）时才必须打开。
3. 最快的办法：安装配套小工具 [`tools/settings-launcher`](../tools/settings-launcher/)（16 KB，无权限），
   一键调用隐藏的系统设置/开发者选项；它也提供「扫描 Settings 类页面」的兜底能力。

---

## 方法 A（推荐）：用「打开设置」小工具

```bash
# 方式 1：从 GitHub Release 下载
#   https://github.com/xzl01/inkread/releases/tag/v0.1.0 附件里的 settings-opener.apk
# 方式 2：本地构建（仓库根目录）
bash scripts/dev-gradle.sh -p tools/settings-launcher :app:assembleRelease
# 产物：tools/settings-launcher/app/build/outputs/apk/release/app-release.apk
```

1. 把 `settings-opener.apk` 拷到设备（USB 文件传输 / SD 卡 / 网盘均可，**不需要 adb**）；
2. 用设备自带的文件管理器点击安装（若提示禁止，去厂商设置里打开「安装未知应用 / 未知来源」）；
3. 打开「打开设置」：
   - 顶部状态区会显示：厂商、型号、Android 版本、**开发者选项/ADB 当前开关**、找到的设置类应用；
   - 先试「打开系统设置」和「打开开发者选项」；
   - 如果都失败（厂商改了包名/类名），点「扫描 Settings 相关页面」，在生成的按钮列表里找
     `DevelopmentSettings`、`DeviceInfoSettings`、`Settings` 等条目逐个点开。

> 小工具不联网、不申请任何权限，源码见 `tools/settings-launcher/`。

## 方法 B：不装任何东西，在设备上自己找

### B1. 厂商自定义设置里的「关于 → 版本号」入口

大多数阅读器的「设置」不是 AOSP 设置，而是厂商自己写的；隐藏的开发者选项通常仍可从「关于本机」解锁：

| 品牌 | 常见路径 |
|---|---|
| 大我 Bigme / Likebook / Meebook | 应用列表 →「设置」→ 关于 → 连点「版本号」7 次；或「系统设置 → 通用 → 开发者选项」 |
| 掌阅 iReader | 设置 → 关于 → 连点「版本号」7 次；部分型号在「设置 → 其他设置 → 开发者选项」 |
| 汉王 Hanwang | 设置 → 关于 → 连点「版本号」7 次；或「系统设置 → 开发者选项」 |
| 文石 Boox | 设置 → 关于 → 连点「版本号」7 次 |
| 墨案 / 其他 | 同理，先找「关于本机 / 系统信息 / 版本信息」页面 |

找不到「设置」图标时的技巧：
- 在桌面/应用抽屉里搜索「设置」「系统设置」「Settings」；
- 从「文件管理 → 应用管理」或任意 App 的「应用信息」页面附近寻找系统设置入口；
- 部分设备下拉通知栏里有齿轮图标（墨水屏多数没有通知栏，可忽略）。

### B2. 浏览器 `intent:` 链接（免安装，成功率看浏览器）

在设备上新建一个本地 HTML 文件（或用任意能打开本地文件的浏览器），内容：

```html
<a href="intent:#Intent;action=android.settings.APPLICATION_DEVELOPMENT_SETTINGS;end">打开开发者选项</a>
```

点击链接。Chrome 内核浏览器支持 `intent:` 跳转；部分国产浏览器会拦截，可换浏览器多试。
同理可换成 `android.settings.SETTINGS`、`android.settings.DEVICE_INFO_SETTINGS`。

### B3. 从其它 App 的「应用信息」页面间接进设置

长按某个 App 的图标/通知 →「应用信息」→ 右上角/更多里有时能进系统设置；进入后返回往往能回到设置主界面。
（不同 ROM 差异较大，仅作兜底。）

---

## 打开开发者选项 + USB 调试（标准步骤）

1. 进入 **设置 → 关于设备 / 关于平板 / 系统信息**；
2. **连续点击「版本号」7 次**（版本号可能在「软件信息」「系统版本」里），直到提示「您已处于开发者模式」；
3. 返回设置主界面 → **系统 / 更多设置 → 开发者选项**；
4. 打开 **「USB 调试」**（部分 ROM 还需打开「通过 USB 安装应用 / USB 安装」）；
5. 部分设备还有厂商开关：「允许 USB 调试」「USB 调试（安全设置）」，一并打开。

---

## 电脑端：确认 adb 并安装 App

```bash
# 1. 确认 adb 已安装（Debian/Ubuntu/Arch 常见包名）
#    sudo apt install android-tools-adb   /   sudo pacman -S android-tools

# 2. 连接设备（USB 数据线，注意有些线只能充电）
adb devices
#   → 显示 unauthorized：在设备上勾选「始终允许使用这台计算机进行调试」并确定
#   → 状态变为 device 才能继续

# 3. 安装墨读
adb install -r dist/inkread-v0.1.0-release.apk
```

常见问题：

| 现象 | 处理 |
|---|---|
| 插上 USB 没反应 | 换数据线（只充电线不行）、换 USB 口；设备端选择「传输文件 / MTP」模式 |
| `no permissions`（Linux） | 把用户加入 `plugdev` 组并重登录，或临时 `sudo adb kill-server && sudo adb start-server` |
| `unauthorized` 反复出现 | 开发者选项里「撤销 USB 调试授权」后重插；删除电脑端 `~/.android/adbkey*` 重试 |
| 设备端没有「USB 调试」选项 | 用方法 A 的小工具确认 Settings 包是否存在；某些型号被厂商限制，需要官方工具/客服 |
| 装不上 APK（解析错误） | 检查设备 Android 版本 ≥ 8.0（本 App minSdk 26）；重新下载 APK 校验 sha256 |

---

## 本项目的推荐流程

1. 用文件管理器侧载 `dist/inkread-v0.1.0-release.apk`（不依赖开发者模式）；
2. 打开「墨读」→ 空状态点「添加订阅源」→ 输入 `https://sspai.com` 或 `https://www.ruanyifeng.com/blog/atom.xml` → 查找 → 订阅；
3. 需要 adb 调试/抓日志时，再按上文打开 USB 调试；
4. 喜欢这个 App 的话，回 GitHub 点个 ⭐️。
