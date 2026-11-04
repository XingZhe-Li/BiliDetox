# 全屏播放器推荐拉条隐藏

分析对象：Bilibili 9.6.0（versionCode 9060300）反编译结果。该功能与详情页「相关推荐」共用 `ConfigData.hideRelatedVideos` 开关，不新增设置项。

## 目标

- 全屏播放时不再装配屏幕右侧的推荐拉条，也不再随点击或左拖弹出推荐卡片面板。
- 不改变详情页相关推荐的现有行为，两者只共享同一个开关。
- 不拦截推荐数据层，避免影响详情页、首页等其它使用同一数据源的页面。
- 策略层保持纯函数（只依赖 `ConfigData`），可在无设备环境离线测试。

## 定位过程

从布局资源反查到控件类，逐层收敛到唯一入口：

| 步骤 | 结果 |
|---|---|
| 布局资源 | `res/layout/theseus_bottom_recommend_cards.xml`（底部 140dp 横向推荐卡片条）、`res/layout/theseus_full_screen_player_bottom_sidebar_item.xml`（178×106dp 推荐卡） |
| layout id | `0x7f0c1c4c`（= 2131500108），在 30 个 dex 中唯一命中 `classes12.dex` |
| 唯一引用 | `ViewBinding` 生成类 `ry0/C1151g.java` |
| 使用方 | `com.bilibili.ship.theseus.ugc.recommend.d` |
| 同包补充 | `i` 的 lambda 名保留了 Kotlin 源文件名，即 `FullScreenPlayingBottomRecommendService`；`j` 是拉条控件本体 |

`j` 的实际行为（决定了 hook 点的取舍）：

- 构造器中自建拉条视图与封面预览。
- `onMeasure` 中调用 `setTranslationX(封面宽 - 62dp)`，把拉条停在屏幕右边缘。
- `dispatchTouchEvent` 在点击、或左拖位移超过 `90dp / 2` 时调用 `b(boolean)`。
- `b()` 通过协程 `showRecommendCards` 把 `d` 以 `layoutType=4` 加入面板。
- `c(List)` 依据相关推荐条数是否大于 3 以及云端配置决定控件可见性。
- `onWidgetActive` 是该控件唯一的装配入口。

## 实现

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/github/xingzheli/bilidetox/hook/PlayerRecommendationsPolicy.kt` | 纯策略：`shouldSuppress(config)` 与 `filterList(config, raw)`，不依赖 Android API，可离线测试 |
| `app/src/main/java/com/github/xingzheli/bilidetox/hook/PlayerRecommendationsHook.kt` | 装配期 hook `com.bilibili.ship.theseus.ugc.recommend.j#onWidgetActive`，开关打开时跳过装配并把控件置 `GONE`；另有 `c(List)` 防御 hook，避免经由其它路径再次显示 |
| `tools/tests/PlayerRecommendationsPolicyTest.kt` | 规则测试，覆盖开关、引用透传、null 与空列表边界 |

开关在调用点读取 `RuntimeConfig.get()`，与其它 hook 一致，设置对话框保存后下一次装配即生效，不需要重启宿主。`filterList` 在开关关闭或入参为 `null` 时原样返回同一引用，只有开关打开且列表非空时才返回空列表，保证调用方可以用 `===` 判断是否需要回写参数。

## 边界与风险

- `j` 与 `c` 是 9.6.0 的混淆名，换版本必须按上述布局 id → ViewBinding → 使用方 的链条重新定位，不能沿用常量。
- 不拦截 `DetailRelateRepository` 等数据层：推荐数据仍会照常请求，只丢弃播放器侧的展示，避免影响详情页与首页。代价是仍有这部分网络与解析开销。
- 跳过整个控件的装配：若未来的宿主版本让该控件承载播放控制等其它能力，需要复核后再收窄拦截范围。
- 编译与真机验证不在仓库自动化范围内（仓库不携带 Android SDK 与签名密钥）；本轮测试包在临时 SDK 上构建、用自签名 keystore 签名，构建与签名细节见交付说明，不进仓库。

## 验证步骤

1. 策略层离线测试（本机已具备的能力）：

   ```sh
   kotlinc app/src/main/java/com/github/xingzheli/bilidetox/HomeConfig.kt app/src/main/java/com/github/xingzheli/bilidetox/TimeLockPolicy.kt app/src/main/java/com/github/xingzheli/bilidetox/ConfigData.kt app/src/main/java/com/github/xingzheli/bilidetox/hook/PlayerRecommendationsPolicy.kt tools/tests/PlayerRecommendationsPolicyTest.kt -include-runtime -d tools/build/player-recommend-policy-tests.jar
   java -cp tools/build/player-recommend-policy-tests.jar PlayerRecommendationsPolicyTestKt
   ```

2. 编译并嵌入 APK（与既有流程一致，见 `docs/BUILD.md`）后安装到设备。
3. `adb logcat -s BiliDetox:V` 应能看到 `PlayerRecommendations` 的 hook 安装日志；找不到目标类时应是告警而不是崩溃。
4. 打开开关，进入任意视频全屏：点击屏幕右侧、或从右边缘左拖，都不再出现推荐拉条与封面预览。
5. 关闭开关后回到全屏，拉条恢复原状，说明开关在运行时读取、无需重装。
6. 同一轮检查详情页相关推荐的开关行为未被改变，确认两者共用开关但互不牵连。
