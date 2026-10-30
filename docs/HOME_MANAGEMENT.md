# 首页管理

入口：我的 → 设置 → BiliDetox 设置 → 首页管理。页内返回按钮或系统返回键可回到主设置。

## 导航页

默认启用，Tab 名称为「导航页」。上方提供胶囊形搜索框，点击直接进入原生搜索页，不在此页唤起键盘；下方单排线条图标进入关注、离线缓存、历史记录、我的收藏、稍后再看。整体靠上排布，余下区域留白。图标用 Canvas 绘制，沿用宿主登录状态并适配深浅主题。

导航页是首页分页容器中的一个基础 Fragment；原生直播和其他信息流 Fragment 不会被装配到该首页中，没有相邻信息流分页需要预加载。

时段锁仍覆盖这个页面和快捷入口跳转后的页面。发誓锁定期间允许浏览首页、使用快捷入口，但首页管理的所有配置均不可修改。

## 原生栏目

关闭导航页后，以下五项分别控制宿主下发的栏目：

| 设置 | 识别地址 |
|---|---|
| 推荐 | `bilibili://pegasus/promo` |
| 热门 | `bilibili://pegasus/hottopic` |
| 直播 | `bilibili://live/home` |
| 动画／番剧 | `bilibili://pgc/bangumi_v2`；旧 `bilibili://pgc/home` 的 `home_flow_type` 不为 `2` |
| 影视 | `bilibili://pgc/cinema_v2`、`bilibili://pgc/cinema-tab`；旧 `bilibili://pgc/home?home_flow_type=2` |

识别时忽略一般埋点参数和片段，但保留旧版动画／影视的语义参数。直播、动画和影视的新旧地址来自 9.6.0 `MainResourceManager.c` 的栏目处理代码。活动栏目保留原版行为，不受这五个开关控制。栏目开关在导航模式下仍可预先配置，关闭导航模式后生效。

若原生列表经过过滤后没有任何可用页面，使用导航页兜底，避免宿主因空列表重新加载包含推荐的原版首页。返回首页时检测配置变化并重建分页；不需要重新安装应用。

## 兼容与实现

- 新配置保存为 `home` 对象。旧 `removeHomeTabs=true` 迁移为隐藏推荐、隐藏热门；旧值为 `false` 则保留两者。动画和影视默认显示，导航页默认启用。其余功能和原誓约保留。
- 配置仍通过 `ConfigEditPolicy` 保存，发誓锁定会冻结这些新增字段。保存失败时开关恢复真实状态。
- 宿主云端缓存保持完整，仅在 `HomeFragmentV2.Kf` 最终装配分页时过滤，覆盖云端和兜底路径。这样重新显示某个栏目时仍能从缓存恢复，不必等待下一次服务端刷新；也避免云端空列表校验触发原版首页回退。
- 导航模式传入空的原生模型列表，再返回一个宿主 `BasePrimaryMultiPageFragment.b` 描述，使用宿主 `androidx.fragment.app.Fragment`。通过带标记的参数，仅为此基础 Fragment 创建快捷入口视图。
- `secondary.c.a.getPage` 的实例化方式、描述字段及 `nd0.g` 字段已从 Bilibili 9.6.0 的 DEX 核对。创建导航页不依赖模块清单注册 Activity 或模块打包 AndroidX。
- `nd0.g` 是纯字段载体；若宿主优化移除了可反射的无参构造器，使用 `Unsafe.allocateInstance` 分配该载体并完整填写所需字段。Fragment 本身仍由宿主适配器按正常构造流程创建。
- 导航页背景和按钮共用正文颜色来判断宿主日夜间主题，避免宿主夜间主题仍返回浅色系统背景属性。
- 配置键 `home.blankHome` 和基础 Fragment 标记保持兼容，仅更新界面名称和布局，不重置任何现有设置。
- 新增 `home.showLive`，旧配置缺失时默认开启以保持旧版直播可见行为；与其他开关一样受全局誓约限制。关闭后在分页创建前移除直播模型。
- 搜索、关注、历史、收藏、稍后再看使用宿主已验证的外部路由。离线缓存使用 `bilibili://offline/cache/home`，通过宿主 `BLRouter.routeTo(RouteRequest, Context)` 调用；该路径不能直接通过外部 `ACTION_VIEW` 打开。接口已核对 9.6.0 DEX；Compose 缓存页实际打开时同步响应仍可能为 `ERROR`，因此交由宿主内部处理响应，仅对调用异常提示失败。

## 规则测试

```sh
kotlinc app/src/main/java/com/github/xingzheli/bilidetox/HomeConfig.kt app/src/main/java/com/github/xingzheli/bilidetox/TimeLockPolicy.kt app/src/main/java/com/github/xingzheli/bilidetox/ConfigData.kt tools/tests/HomeTabPolicyTest.kt tools/tests/ConfigEditPolicyTest.kt tools/tests/TimeLockPolicyTest.kt -include-runtime -d tools/build/home-lock-policy-tests.jar
java -cp tools/build/home-lock-policy-tests.jar HomeTabPolicyTestKt
java -cp tools/build/home-lock-policy-tests.jar ConfigEditPolicyTestKt
java -cp tools/build/home-lock-policy-tests.jar TimeLockPolicyTestKt
```

覆盖五个栏目相互独立、动画／影视新旧地址和参数区分、旧配置迁移、未知地址保留，以及全部首页选项的全局誓约限制。

## 真机验证（2026-10-30）

- vivo V2118A、Android 14、Bilibili 9.6.0，普通非 debuggable 宿主包 `9060308` 覆盖安装成功；APK 签名校验通过，内嵌模块与本次 Gradle 构建产物逐字节一致。
- 冷启动显示一个空白分页和四个按钮，日志确认未装配原生信息流分页，`dumpsys media_session` 没有 Bilibili 播放会话。夜间截图确认背景为深色、按钮文字可读。
- 从空白页逐个点击搜索、关注、个人收藏、浏览历史，分别进入原生搜索页、关注页、我的收藏、历史记录；返回后仍为空白首页。进入「我的」及设置正常。
- 首页管理有独立子页面；系统返回键可回到模块主设置。现有誓约 `2026-10-31 17:17` 保留，五个首页开关全部禁用；原有六个其他功能开关的开启状态和禁用状态均保留。
- 未解除用户的有效誓约，因此本轮未在真机上切换原生栏目组合；独立过滤和誓约到期后的编辑由上述 JVM 规则测试验证。没有对网络请求做抓包，媒体会话检查不等价于网络流量测量。

### 导航页布局改版（2026-10-30）

- 普通包 `9060315` 覆盖安装成功，签名校验通过，嵌入模块与 Gradle 构建产物一致。Tab 显示「导航页」，上方搜索框、下方五个图标的浅色和深色实机截图均已检查。
- 从新版布局实际点击搜索、关注、离线缓存、历史记录、我的收藏、稍后再看，均进入对应的原生页面，返回后仍为导航页。离线缓存交由宿主内部路由处理后，不再误报同步响应的错误码。
- 导航模式冷启动没有 Bilibili 媒体会话。测试时原导航模式处于关闭状态，已通过设置页开启以展示改版；其余四个栏目开关原值保留，誓约仍未启用。深色检查完成后恢复测试前的浅色主题。

### 直播开关与动画／影视过滤修复（2026-10-30）

- 旧规则仅识别 `pgc/home`，遗漏宿主新增的 `pgc/bangumi_v2`、`pgc/cinema_v2` 和 `pgc/cinema-tab`，导致动画、影视关闭后仍显示。已补齐全部新旧地址，并添加独立的「显示直播」开关。
- 普通包 `9060316` 的构建、APK 签名及嵌入模块一致性检查通过。JVM 测试覆盖所有新旧栏目地址的独立开关、直播默认兼容和直播开关的全局誓约限制。
- 连续通过真实设置页切换组合，返回原生首页逐一检查 Tab，五组均通过：

| 直播 | 动画 | 影视 | 实际对应栏目 |
|---|---|---|---|
| 关闭 | 关闭 | 关闭 | 三者均隐藏 |
| 关闭 | 开启 | 关闭 | 仅动画 |
| 关闭 | 关闭 | 开启 | 仅影视 |
| 开启 | 关闭 | 关闭 | 仅直播 |
| 开启 | 开启 | 开启 | 三者均显示 |

活动栏目在各组中保持显示；从关闭切回开启无需重装或清除数据，完整缓存可直接恢复栏目。

测试结束后已恢复首页管理全部六个开关原值：导航页关闭、推荐关闭、热门关闭、直播开启、动画关闭、影视关闭。再次冷启动确认仅显示直播及活动栏目，动画、影视没有重新出现；其余功能及誓约不变。
