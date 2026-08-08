# BiliDetox

> 你打开B站，是为了看你想看的东西，还是为了看算法觉得你该看的东西？  
> 这个脚本把首页的"推荐"和"热门"直接干掉。你的首页，你说了算。  
> 用户应该有选择"被推荐"的权力——包括选择**不被推荐**。  

一个面向 Bilibili 安卓客户端的 Xposed 模块，通过 NPatch 直接嵌入目标 APK，不需要 root。

当前功能：

- 移除首页顶栏的「推荐」和「热门」Tab
- 禁用应用内更新检查（启动检查、手动检查、已缓存的强更提示、内部升级埋点）

已验证版本：**Bilibili 9.6.0 (versionCode 9060300)**。其他版本需要自行测试——hook 点尽量使用了未混淆的类名和接口方法名，但 B 站随时可能改版。

## 它在做什么

B 站首页顶栏的 Tab 列表在运行时由 `tv.danmaku.bili.ui.main2.resource.MainResourceManager` 生成，包含云端下发和硬编码兜底两条路径。模块 hook 了这条数据流上的两个收敛点：

1. `MainResourceManager#c(int, List)` —— 云端数据的入口，在返回时过滤掉目标 Tab
2. `HomeFragmentV2#Kf(List)` —— 真正构建 Fragment 页面前的总闸，覆盖所有兜底路径

过滤时做了两件事：

- **保留至少一个 Tab**。B 站在 Tab 列表为空时会判定数据无效、回退到硬编码兜底，推荐 Tab 反而会重新出现。
- **转移默认选中标记**。推荐 Tab 原本是 `defaultSelected`，删除后若无人接手，初始选中状态会异常。

更新禁用直接替换四个未混淆的 `UpdateHelper` 静态方法，让它们立即返回。

具体设计和权衡见源码注释。所有 hook 点都来自对 9.6.0 APK 的反编译，不是猜测。

## 使用

### 前置条件

- 一台安卓真机（模拟器也可以，但 B 站只有 arm64-v8a 原生库，x86 模拟器需要 ARM 翻译层，可能很慢或不稳定）
- ADB
- JDK 17+
- Android SDK

NPatch 的命令行 jar（v1.0.6-698，即 NPatch 官方 release）已随仓库放在 `tools/npatch.jar`。升级时替换该文件即可。

### 一键构建并打包

把未修改的 Bilibili 9.6.0 APK 放到 `original/iBiliPlayer-bili.apk`（文件路径和命名不强制，脚本接受参数），然后：

```cmd
patch.bat
```

产物写入 `output/iBiliPlayer-bili-698-npatched.apk`。

脚本依次执行：编译模块 → 编译 NPatch 启动器 → 用 NPatch 把模块嵌入原 APK。完整说明见 [`docs/BUILD.md`](docs/BUILD.md)。

### 安装

```cmd
adb install -r output\iBiliPlayer-bili-698-npatched.apk
```

NPatch 会用自己的 keystore 重新签名，因此**安装前必须卸载任何已有的官方 Bilibili**（签名冲突无法覆盖安装）。

启动后用日志确认模块已加载：

```cmd
adb logcat -s BiliDetox:V
```

正常应看到：

```
BiliDetox: 已注入 tv.danmaku.bili (tv.danmaku.bili)
BiliDetox: 已 hook MainResourceManager#c
BiliDetox: 已 hook HomeFragmentV2#Kf
BiliDetox: 禁用更新：已拦截 4 个方法
BiliDetox: [MainResourceManager#c] 已移除 2 个 Tab: [bilibili://pegasus/promo, bilibili://pegasus/hottopic]，剩余 N 个
```

## 改要移除哪些 Tab

目前没有设置界面，所有开关硬编码在
[`app/src/main/java/com/github/xingzheli/bilidetox/Config.kt`](app/src/main/java/com/github/xingzheli/bilidetox/Config.kt)。

`REMOVED_TAB_URIS` 可选值（来自 9.6.0 的硬编码兜底列表）：

| URI | Tab |
|---|---|
| `bilibili://pegasus/promo` | 推荐（默认选中页） |
| `bilibili://pegasus/hottopic` | 热门 |
| `bilibili://live/home` | 直播 |
| `bilibili://pgc/home` | 番剧 |
| `bilibili://pgc/home?home_flow_type=2` | 影视 |

注意：uri 匹配忽略 query，所以 `bilibili://pgc/home` 会同时命中番剧和影视（影视只多了 `?home_flow_type=2`）。promo 和 hottopic 不存在这个歧义。修改后重新 `patch.bat` 并安装即可。

两个开关也在这里：`REMOVE_HOME_TABS` 和 `BLOCK_UPDATE`。

## 项目结构

```
BiliDetox/
  app/                              模块源码
    src/main/java/.../Config.kt     硬编码开关
    src/main/java/.../XposedInit.kt 模块入口
    src/main/java/.../hook/         各 hook 实现
    src/main/assets/xposed_init     Xposed 入口声明
  tools/
    npatch.jar                      NPatch CLI（已内置）
    NPatchLauncher.java             桌面 JDK 下的 BKS 修复
  original/                         放原版 Bilibili APK（git-ignored）
  output/                           打包产物（git-ignored）
  docs/BUILD.md                     构建细节与踩坑记录
  patch.bat                         一键构建+嵌入
```

## 实现上的两个坑

这两点在 `docs/BUILD.md` 里有完整说明，简述如下：

1. **NPatch 在桌面 JDK 上报 `BKS KeyStore not available`。** 它需要的 BouncyCastle 其实就在自己的 jar 里，只是没注册。`tools/NPatchLauncher.java` 在调用 NPatch main 前完成注册。
2. **必须用 `-l 0` 关闭签名绕过。** NPatch 默认的 `-l 1` 在 PC 打包环境下走嵌套 zip 链接路径，链接失败时静默丢弃全部 dex，产物缺主 dex 装不上。`-l 0` 走普通复制，产物完整。B 站不做运行时自签名校验，功能不受影响。

## 致谢

模块结构参考了 [BiliRoaming](https://github.com/yujincheng08/BiliRoaming) 的组织方式（入口、hook 拆分、日志封装），但所有 hook 点都针对 9.6.0 重新定位——BiliRoaming 最后一次更新时的目标版本与现在差距很大，旧 hook 大部分已经失效。

## 免责声明

本项目仅用于个人学习研究。修改过的 APK 不得用于分发或商业用途。使用本模块产生的一切后果由使用者自行承担。
