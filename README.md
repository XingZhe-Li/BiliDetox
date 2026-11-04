# BiliDetox

> 你打开B站，是为了看你想看的东西，还是为了看算法觉得你该看的东西？  
> 这个脚本把首页的"推荐"和"热门"直接干掉。你的首页，你说了算。  
> 用户应该有选择"被推荐"的权力——包括选择**不被推荐**。  

一个面向 Bilibili 安卓客户端的 Xposed 模块，通过 NPatch 直接嵌入目标 APK，不需要 root。

当前功能（均可在应用内设置页运行时开关）：

- 首页管理：推荐、热门、直播、动画、影视独立开关；默认使用带搜索框及关注、离线缓存、历史、收藏、稍后再看入口的导航页
- 禁用应用内更新检查（启动检查、手动检查、已缓存的强更提示、内部升级埋点）
- 广告净化（设置 → 净化管理）：拦截详情页广告面板——暂停后的广告弹层与播放器下方的推荐广告（默认开启，可关闭）
- 视频页隐藏「相关推荐」
- 视频页隐藏评论区
- 搜索框隐藏自动填充的话题（默认搜索词）
- 搜索页隐藏「bilibili热搜」「搜索发现」板块
- 每日多时段使用锁（默认关闭，预置 23:00–次日 06:00），支持带终止时间的「发誓锁定」
- 综合搜索严格过滤视频：按标题、搜索接口简介、UP主名称匹配（默认关闭）

已验证版本：**Bilibili 9.6.0 (versionCode 9060300)**。其他版本需要自行测试——hook 点尽量使用了未混淆的类名和接口方法名，但 B 站随时可能改版。

## 设置入口

打开 B 站 **我的 → 设置**，列表末尾的「**BiliDetox 设置**」即是配置面板：
所有开关即时保存（JSON 存于 B 站数据目录），回到对应页面后生效，无需重启应用。

各功能的 hook 原理简述（B 站特定 hook 基于 9.6.0 APK 的反编译，时段锁使用 Android 生命周期与媒体会话入口，详见源码注释）：

时段锁的规则、发誓锁定及测试方式见 [时段锁说明](docs/TIME_LOCK.md)。时段锁、发誓锁各有独立二级设置页。普通锁启用当天不能关闭，次日可在可用时段关闭；发誓锁可独立冻结全部设置，不自动启用时段锁。发誓锁定到期前，BiliDetox 的所有开关及其他可编辑选项都不能修改，到期后已启用的每日时段锁仍继续生效。

| 功能 | hook 点 |
|---|---|
| 首页栏目显示 | `HomeFragmentV2#Kf` 最终装配前过滤，保留完整云端缓存 |
| 导航页 | 在 `HomeFragmentV2#Kf` 创建分页前替换为宿主基础 Fragment，只创建搜索与图标入口 |
| 禁用更新 | `UpdateHelper` 四个静态方法 |
| 详情页广告面板 | `AdPanelRepository#showPanel(int, IPanelData, IAdPanelListener)` 全部 panelType 前置拦截（3/4 为暂停广告，其余为播放器下方推荐广告） |
| 隐藏相关推荐 | `IntroRecycleViewService` 初始列表 + 信息流插入 |
| 隐藏评论区 | `united.di.y#a` 组装 DetailTabs 时移除评论 Tab |
| 隐藏搜索占位词 | `UpdateSearchDefaultWordAction` 载体置空 |
| 隐藏热搜/搜索发现 | `search2.discover.p#f` 分发前过滤 square |
| 综合搜索严格过滤 | `search2.utils.f#a(SearchAllResponse, String, …)` 映射前过滤 AV 卡片 |

广告净化集中在「净化管理」二级页，开关项为 `PurifyConfig#blockVideoDetailAdPanel`（默认开启）。它只拦广告**面板**：广告请求仍会发出，其它 UI 不受影响。详情页「相关推荐」里的广告卡片不归这里管理——它随「视频页隐藏「相关推荐」」的组件过滤一起隐藏。范围、边界与验证见 [净化管理](docs/PURIFY_MANAGEMENT.md)。

严格过滤需要在 BiliDetox 设置中开启，重新搜索后生效。忽略大小写、全半角及一般标点（保留 C++ / C# 的符号），查询使用结巴中文分词（INDEX 模式，包含词典内的子词），去除常见虚词和中文单字噪声；任一有效词在标题、简介或UP主名称中命中即保留，单个词不会跨字段拼接。英文、数字及 C++ / C# 保持完整。没有有效分词时回退到完整查询。每次查询的分词结果缓存，最多保留 32 个查询。过滤开启时，应用启动后在后台预加载词典；运行中开启过滤也触发预加载，每个进程只加载一份。若立即搜索且加载未完成，仍可能等待剩余时间。验证仅检查第一页前 10 个普通 AV 视频；第一页不足 10 条就检查实际全部视频，不跨页补足。验证范围内至少一条匹配时，只有整次搜索前 5 条普通视频获得豁免，其余逐条按关键词匹配；验证全部不匹配则取消豁免，屏蔽整个列表并停止分页。广告和用户卡片不占验证名额，验证通过后保留，验证失败时随列表隐藏。不做别名映射。简介仅使用搜索响应已有内容，经测试部分响应为空，不额外拉取详情。不使用评论、字幕或推荐理由，因此会漏掉仅在这些内容中相关的视频，可关闭开关恢复。当前仅覆盖综合搜索的普通 AV 视频，不单独判定广告卡片、用户卡片的关键词相关性，不覆盖其他分类页；不改变首页或视频详情页。

## 它在做什么

B 站首页顶栏的 Tab 列表由 `tv.danmaku.bili.ui.main2.resource.MainResourceManager` 提供，包含云端下发和硬编码兜底两条路径。模块保留完整缓存，仅在 `HomeFragmentV2#Kf(List)` 真正构建页面前选择首页模式，覆盖所有兜底路径。

- **导航模式**只装配一个带搜索框和图标入口的基础 Fragment，不创建直播和其他信息流分页，避免相邻分页预加载视频流。
- **原生模式**按独立开关过滤推荐、热门、直播、动画、影视。覆盖动画、影视的新旧栏目地址；复制模型后转移默认选中标记，避免污染共享缓存，重新开启栏目时仍能从缓存恢复。
- **空列表兜底**使用同一个导航页，不触发宿主回退到包含推荐的原版首页。

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

```sh
python patch.py                # 默认：只嵌入模块
python patch.py --libpatch     # 额外对 libbili.so 打网络探测补丁
```

产物写入 `output/`，脚本结束时会打印实际路径。产物名由 NPatch 从输入名派生：默认 `iBiliPlayer-bili-698-npatched.apk`；带 `--libpatch` 时为 `iBiliPlayer-bili-libpatched-698-npatched.apk`。

`--libpatch` 默认关闭：该补丁与 B 站版本强绑定（按 BuildId 校验，当前仅支持 9.6.0，换版本会报错退出）。它用于修复部分机型上 B 站自带网络探测线程引发的 `stack corruption detected` 崩溃，取证与补丁细节见 [`tools/patch_libbili.py`](tools/patch_libbili.py)。

脚本依次执行：编译模块 → 编译 NPatch 启动器 →（可选）补丁 libbili.so → 用 NPatch 把模块嵌入原 APK。完整说明见 [`docs/BUILD.md`](docs/BUILD.md)。

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
BiliDetox: 已安装首页管理：空白模式与独立栏目开关
BiliDetox: 禁用更新：已拦截 4 个方法
BiliDetox: 导航页已创建，未装配原生信息流页面
```

## 首页管理

进入 **我的 → 设置 → BiliDetox 设置 → 首页管理**。默认「导航页」上方提供点击跳转的搜索框，下方一排图标分别进入关注、离线缓存、历史记录、我的收藏、稍后再看，不创建直播等信息流页面。关闭导航页后，可以分别控制推荐、热门、直播、动画、影视的显示，活动栏目保留原版行为。动画与影视识别新版独立地址及旧版 `home_flow_type` 参数，互不影响。

旧的「移除推荐/热门」开关自动迁移为两个独立选项。更改即时保存，返回首页时重建页面；启用发誓锁定后，这些选项也不可修改。规则、实现和验证方式见 [首页管理说明](docs/HOME_MANAGEMENT.md)。

## 项目结构

```
BiliDetox/
  app/                              模块源码
    src/main/java/.../HomeConfig.kt 首页模式与栏目规则
    src/main/java/.../XposedInit.kt 模块入口
    src/main/java/.../hook/         各 hook 实现
    src/main/assets/xposed_init     Xposed 入口声明
  tools/
    npatch.jar                      NPatch CLI（已内置）
    NPatchLauncher.java             桌面 JDK 下的 BKS 修复
    patch_libbili.py                libbili.so 网络探测补丁（含取证记录）
  original/                         放原版 Bilibili APK（git-ignored）
  output/                           打包产物（git-ignored）
  docs/BUILD.md                     构建细节与踩坑记录
  patch.py                          一键构建+嵌入（跨平台）
```

## 实现上的两个坑

这两点在 `docs/BUILD.md` 里有完整说明，简述如下：

1. **NPatch 在桌面 JDK 上报 `BKS KeyStore not available`。** 它需要的 BouncyCastle 其实就在自己的 jar 里，只是没注册。`tools/NPatchLauncher.java` 在调用 NPatch main 前完成注册。
2. **必须用 `-l 0` 关闭签名绕过。** NPatch 默认的 `-l 1` 在 PC 打包环境下走嵌套 zip 链接路径，链接失败时静默丢弃全部 dex，产物缺主 dex 装不上。`-l 0` 走普通复制，产物完整。B 站不做运行时自签名校验，功能不受影响。

## 致谢

模块结构参考了 [BiliRoaming](https://github.com/yujincheng08/BiliRoaming) 的组织方式（入口、hook 拆分、日志封装），但所有 hook 点都针对 9.6.0 重新定位——BiliRoaming 最后一次更新时的目标版本与现在差距很大，旧 hook 大部分已经失效。

## 免责声明

本项目仅用于个人学习研究。修改过的 APK 不得用于分发或商业用途。使用本模块产生的一切后果由使用者自行承担。
