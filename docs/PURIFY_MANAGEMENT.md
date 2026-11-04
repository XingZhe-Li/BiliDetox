# 净化管理（广告拦截）

设置路径：**我的 → 设置 → BiliDetox 设置 → 净化管理**。所有开关即时保存（JSON 存在 B 站数据目录），回到视频页后生效。

## 范围

| 开关 | 覆盖范围 | 默认 |
|---|---|---|
| 详情页广告面板 | 暂停后的广告弹层、播放器下方广告视图与广告面板 | 开启 |

**不归这里管**：详情页「相关推荐」列表里的广告卡片。它属于「视频页隐藏「相关推荐」」的组件过滤范围（`intro.module.relate.` 前缀包含 `relate.ad.`），由主界面那个开关控制。

## 实现

面板注入点：`com.bilibili.ship.theseus.united.page.ad.AdPanelRepository#showPanel(int, IPanelData, IAdPanelListener)`。

```java
// 9.6.0 反编译（analysis/jadx14/.../page/ad/AdPanelRepository.java:850）
public final <T extends IPanelData> void showPanel(int i, @Nullable T t, @Nullable IAdPanelListener l) {
    if (i == 3 || i == 4) { /* 暂停页广告：new AdPanelHelper(...).showPanel() */ }
    else                  { /* under-player / upper 广告面板 */ }
}
```

`DetailAdPanelHook` 在方法调用前判断开关。暂停广告（类型 3/4）需要先同步调用已确认的宿主关闭回调，清理暂停状态和倒计时；如果监听器或清理方法无法确认，或清理失败，则保留宿主原流程。其他类型的面板直接拦截。

播放器下方广告视图还有独立路径：`DetailUnderPlayerAdHook` 将可见性回调交给宿主的隐藏分支处理，并拦截直接显示容器的调用。广告数据及视图仍可能被请求或创建；这里只控制已定位的展示路径，不保证拦截所有广告或曝光上报。开关关闭时放行，设置变更后重新进入视频页生效。

## 边界与风险

- `panelType` 3/4 的语义依据同分支日志 `"showPanel ignored, paused page panel already showing"`；其它取值目前按「也是广告面板」处理（该仓库只构造 `AdPanelHelper`）。若未来该入口被复用成非广告面板，必须改成白名单。
- 类名是语义名（`AdPanelRepository`），但方法名 `showPanel` 与常量取值是版本绑定；换版本需重新核对。
- 暂停广告倒计时可能在面板调用之前显示；若请求中途取消且未到达面板拦截点，本功能不会清除该提示。
- 播放器下方可见性回调的类名和签名同样基于 9.6.0；不匹配时该路径不会生效。
- 安装 hook 前做「类存在 + 三参方法存在 + `IPanelData`/`IAdPanelListener` 可加载」校验，失败只 `Log.w`，不影响宿主启动。
- 发誓锁定期间，本开关与其它开关一样不可修改（见 `tools/tests/ConfigEditPolicyTest.kt` 的穷举断言）。

## 验证

1. 无设备单测：`tools/tests/ConfigEditPolicyTest.kt` 覆盖发誓锁冻结新开关；`tools/tests/AdPanelSuppressionTest.kt` 覆盖暂停监听器清理与无法确认时放行。

   ```sh
   kotlinc app/src/main/java/com/github/xingzheli/bilidetox/hook/AdPanelSuppression.kt tools/tests/AdPanelSuppressionTest.kt -include-runtime -d tools/build/ad-panel-tests.jar
   java -jar tools/build/ad-panel-tests.jar
   ```

2. 构建：`:app:assembleDebug` 通过；NPatch 嵌入后包内 dex 可反查到 `DetailAdPanelHook`。
3. 真机：
   - 在确实收到暂停广告或播放器下方广告的样本中，对比开关开启和关闭的显示与日志，并检查暂停、恢复及再次暂停。
   - 开关关闭：暂停广告与下方推荐广告恢复原样。
   - 回归：评论区、简介 Tab、全屏右侧推荐拉条、首页导航页正常。
