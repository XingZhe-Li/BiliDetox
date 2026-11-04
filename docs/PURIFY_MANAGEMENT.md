# 净化管理（广告拦截）

设置路径：**我的 → 设置 → BiliDetox 设置 → 净化管理**。所有开关即时保存（JSON 存在 B 站数据目录），回到视频页后生效。

## 范围

| 开关 | 覆盖范围 | 默认 |
|---|---|---|
| 详情页广告面板 | 暂停后的广告弹层（服务端 `PlayPause` 下发，`PAUSE_TYPE_ADS`）+ 播放器下方的推荐广告面板 | 开启 |

**不归这里管**：详情页「相关推荐」列表里的广告卡片。它属于「视频页隐藏「相关推荐」」的组件过滤范围（`intro.module.relate.` 前缀包含 `relate.ad.`），由主界面那个开关控制。

## 实现

唯一注入点：`com.bilibili.ship.theseus.united.page.ad.AdPanelRepository#showPanel(int, IPanelData, IAdPanelListener)`。

```java
// 9.6.0 反编译（analysis/jadx14/.../page/ad/AdPanelRepository.java:850）
public final <T extends IPanelData> void showPanel(int i, @Nullable T t, @Nullable IAdPanelListener l) {
    if (i == 3 || i == 4) { /* 暂停页广告：new AdPanelHelper(...).showPanel() */ }
    else                  { /* under-player / upper 广告面板 */ }
}
```

`DetailAdPanelHook` 在该方法 `before` 阶段判断开关，开启时 `setResult(null)`：面板对象、浮层与入场动画都不会创建，因此既不会闪现也不会留白。开关关闭时首行早退，行为与官方一致。

只拦面板层：广告请求仍会发出（本 hook 不改请求与服务端判定），曝光不会上报。

## 边界与风险

- `panelType` 3/4 的语义依据同分支日志 `"showPanel ignored, paused page panel already showing"`；其它取值目前按「也是广告面板」处理（该仓库只构造 `AdPanelHelper`）。若未来该入口被复用成非广告面板，必须改成白名单。
- 类名是语义名（`AdPanelRepository`），但方法名 `showPanel` 与常量取值是版本绑定；换版本需重新核对。
- 安装 hook 前做「类存在 + 三参方法存在 + `IPanelData`/`IAdPanelListener` 可加载」校验，失败只 `Log.w`，不影响宿主启动。
- 发誓锁定期间，本开关与其它开关一样不可修改（见 `tools/tests/ConfigEditPolicyTest.kt` 的穷举断言）。

## 验证

1. 无设备单测：`tools/tests/ConfigEditPolicyTest.kt` 覆盖「发誓锁定冻结全部开关」，新增的 `purify.blockVideoDetailAdPanel` 已纳入穷举列表。
2. 构建：`:app:assembleDebug` 通过；NPatch 嵌入后包内 dex 可反查到 `DetailAdPanelHook`。
3. 真机：
   - 开关开启：竖屏/半屏/全屏暂停 5~10 秒不再出现广告面板；播放器下方不再出现推荐广告面板；logcat 出现 `已拦详情页广告面板 panelType=N`。
   - 开关关闭：暂停广告与下方推荐广告恢复原样。
   - 回归：评论区、简介 Tab、全屏右侧推荐拉条、首页导航页正常。
