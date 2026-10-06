# 搜索结果推荐过滤：9.6.0 二进制分析与设计

分析日期：2026-10-06。分析对象为仓库 `original/iBiliPlayer-bili.apk`，SHA-256：
`df9da4022bc5e5e02fd36c80235e48603dcb848877c979f6190450d972a37d1c`。

已完成静态分析及 Android 14 / vivo V2118A 上的运行时取证，新增默认关闭的诊断 hook，尚未添加生产过滤 hook。结论只针对该 APK 和采集样本；服务端推荐算法不能由客户端二进制确定。下面的静态设计由文末真机结论修订，不能把“提示卡区块过滤”当作完整解决方案。

## 现有功能

以 `XposedInit.kt`、`RuntimeConfig.kt` 和各 hook 源码为准：

| 功能 | 默认状态 | 实现范围 |
| --- | --- | --- |
| 移除首页推荐、热门 | 开启 | 云端 Tab 数据及页面构建兜底 |
| 禁用应用内更新 | 开启 | UpdateHelper 四个方法 |
| 隐藏视频相关推荐 | 关闭 | 简介初始组件和动态插入组件 |
| 隐藏评论 | 关闭 | 详情页评论 Tab |
| 隐藏默认搜索词 | 关闭 | 搜索词数据及相关显示路径 |
| 隐藏热搜、搜索发现 | 关闭 | 发现页 square 的 trending/recommend；保留历史 |

另有设置入口和 JSON 配置持久化，以及可选、版本锁定的 libbili.so 网络探测崩溃补丁。当前没有搜索结果推荐过滤。README 后半部分关于“没有设置界面”和硬编码开关的说明已过时。

## 分析方法及证据

直接读取 APK 中 DEX 的字符串表、类型表和 class_def 表定位搜索实现，提取 `classes12.dex`，用本机 JADX 反编译。原始 DEX 方法表另行确认签名，避免把 JADX 自动重命名误作运行时类名。

临时证据位于 git 忽略的 `output/search-analysis/`：`classes12.dex`、`decompiled/sources/` 和 `raw-signatures.json`。JADX 对整个 DEX 有部分反编译错误，尤其大型映射方法有明显不可信的还原控制流；以下方案不依赖这些错误分支作为正确性依据。实现前应对候选方法导出 smali/指令并复核。

### 搜索响应中的推荐提示

`com.bilibili.search2.api.BaseSearchItem` 有 `getGoTo()`，对应 JSON `goto`；还包含 `card_type`、`rcmd_reason`、服务端位置和追踪信息。

`com.bilibili.search2.result.all.r` 注册以下类型：

- `recommend_tips` → `SearchRecommendTipItem` → `Xw0.b`。
- `hot_recommend` → `SearchRecommendTipItem` → `Xw0.a`。
- `query_rec_afterclick` → `result.holder.recommend.q` → `result.holder.recommend.o`。

`SearchRecommendTipItem` 从 protobuf `SearchRecommendTipCard` 或 `SearchHotRecommend` 获取标题与封面。它是提示卡，不是推荐视频集合。

原始 DEX 确认综合搜索映射入口：

```text
com.bilibili.search2.utils.f#a(
  com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse,
  java.lang.String, int, ow0.P, int
) -> com.bilibili.search2.api.SearchResultAll
```

JADX 将 `utils.f` 显示为 `C0831f`，运行时必须使用原名。返回模型的公开字段 `items` 是 `List<BaseSearchItem>`；另有 `foldedItems`、卡片分组及分页信息。垂直搜索也出现推荐提示卡，不能只覆盖综合页。

### 列表进入页面前的收敛点

原始 DEX 确认 `com.bilibili.search2.result.base.w#u0(java.util.List, boolean, boolean): void`。该方法识别 `recommend_tips`/`hot_recommend`，更新内部标记 `d`，向传入列表追加 footer，再计算 DiffUtil 并替换列表。

`result.base.q` 的垂直结果状态处理也用两种 goto 设置 `hasNoResultCard`。因此只在绑定 ViewHolder 时隐藏视图，会留下列表位置、布局和状态；只删标题也会留下推荐视频。两处消费者证明这是明确的客户端分支，但不能证明“提示之后每一项都是无关视频”。

### 点击后的推荐是另一项功能

`SearchResultAllViewModel$searchRecAfterQuest$2` 构造 `QueryRecAfterClickReq`，经 `SearchMossKtxKt.suspendQueryRecComment` 请求数据。输入包含 keyword、点击 URL、位置、trackId 和 userAct。

仅接受 `goto == query_rec_afterclick` 的结果，将 `QueryRecList` 的 showName、URL、图标和推荐理由装配为 `result.holder.recommend.q`。这里是推荐搜索词入口，不是本需求中的无关视频列表，应作为独立可选功能处理。

### 无结果推荐接口

`SearchService#searchRecommend` 声明 `x/v2/search/recommend/noresult`，返回 `GeneralResponse<SearchResultAll>`。静态声明支持存在独立无结果推荐路径，但本次尚未确认其活跃调用链；不能仅拦它就宣称覆盖所有推荐。

### 无法可靠判断的内容

普通 `SearchVideoItem` 也会接收 `SearchVideoCard.rcmdReason`。推荐理由非空、标题不含关键词、排序靠后或 `isInAlienationArea` 均不足以证明视频与搜索无关。异化区还存在折叠和分组逻辑，不应全删。服务器把无关内容混入普通视频且不提供区别标记时，当前静态分析不能实现无误删识别。

## 建议的过滤设计

新增独立开关 `hideSearchResultRecommendations`，默认关闭，沿用 RuntimeConfig 和设置面板。初期先支持有明确来源或边界的推荐区块。

1. **先做最小运行时取证。** 在映射返回后记录每页卡片的类名、goto、cardType、页码、服务端位置和分组标识，以及分页是否结束；分别测试正常命中、稀少命中、完全无结果、加载更多及点击返回。不记录关键词、标题、用户行为和追踪值。确认两种提示后面的推荐是否连续、是否跨页、是否可能再次出现正常搜索结果。
2. **按来源过滤。** 如果确认某条活跃路径专门返回无结果推荐，过滤这一路径的视频数据；优先在响应转换完成、合并状态之前替换列表副本。综合映射方法的 after 阶段是候选位置，垂直搜索需补充对应入口。
3. **按已证实的区块边界过滤。** 两种提示可作为候选起点，但只有运行时和指令验证确定区间后，才删除提示及所属视频。不要现在就写“遇到提示截断本页剩余所有卡片”。跨页边界状态须按搜索会话持有，在关键词/筛选/排序变化和刷新时重置，不能使用一个全局 Boolean。
4. **保留分页语义。** 保留服务端页号和游标，区分原始响应空与过滤后空；只有确认进入纯推荐尾部且没有后续正常结果时才将 UI 标为结束。不得因过滤后空反复自动拉页，也不得凭列表长度修改服务端位置。检查综合响应 `isEmpty()`、状态合并、`hasNoResultCard`、adapter `d` 和 footer 的联动。
5. **覆盖隐藏列表与分组。** 如目标卡片进入 `foldedItems` 或分组子列表，同步过滤，避免展开后重新出现；重新计算受影响的本地分组数据。不要仅改 `items` 而保留指向已删除项的分组/折叠元数据。
6. **呈现干净空态。** 无正常结果时显示无搜索结果和返回/修改搜索入口。过滤发生在数据层，避免透明占位；不开启自动播放或新的推荐兜底。
7. **独立处理推荐词。** 如另加“隐藏点击后推荐搜索词”，在其装配/插入边界过滤 `query_rec_afterclick`。协程方法不能随意返回 null，应使用合法的空数据/空 Flow 或跳过明确的插入事件。

`w#u0(List,ZZ)` 可作为显示层兜底候选，但不是唯一主入口：它会修改列表，且收到空列表会直接返回，可能保留旧页面。必须传可变副本，并验证清空、刷新及追加路径；不要用它掩盖状态层未处理的问题。

版本不匹配、方法签名不匹配或字段读取失败时保留原始数据并记录简短日志，不能抛异常影响宿主。类名混淆变化时按签名和返回类型重新定位，不使用宽泛的同名 hook。

## 验收与剩余工作

- 正常搜索、筛选、排序、视频/番剧/用户等垂直页的正常内容保留。
- 无结果或结果耗尽时，已标定推荐区的视频和提示共同移除。
- 初始加载、刷新、分页、展开折叠、点击返回均不恢复推荐，不残留空白行。
- 全部删除时显示正确空态；无分页死循环、旧结果残留、footer 卡住或 DiffUtil 崩溃。
- 切换搜索会话后过滤状态不泄漏；开关关闭并重新加载后恢复原始行为。
- 标题不含原始关键词但实际相关的正常视频不被误删。

静态阶段的原计划是补齐 smali 复核和真机卡片序列取证，再据实际区块边界实现 hook。下节的真机结果说明，本需求还需要覆盖无专用区块标记的普通视频，不能直接按原计划截断列表。

## 真机取证与修订结论（2026-10-06）

### 本次需求的准确范围

目标是提交关键词后的搜索结果页中，与搜索意图无关的视频条目。首页推荐、视频详情页相关推荐、搜索发现、推荐搜索词都不是本次目标。不能仅删除推荐提示标题，或把搜索发现隐藏功能视为完成本需求。

### 环境与操作

设备：vivo V2118A / Android 14。已安装 Bilibili 9.6.0、versionCode 9060300，且已经嵌入 BiliDetox。先将当前安装 APK 备份到 `output/search-analysis/device-installed.apk`，再通过 `adb install -r` 覆盖安装两版临时诊断模块，应用数据未清除。测试用输入进入搜索历史；输入法曾改写或丢失字符，所有有效样本以页面实际显示的查询为准。

有效样本包括：长数字 `731829460517328` 及另一条长数字搜索、正常对照词 `100`。前者在综合搜索页面呈现计算机、历史、娱乐等无关视频；后者保留了含 100 的正常视频，也含广告和其他卡片。本次未逐条人工标注所有普通文本的语义相关性。

### 捕获到的真实响应结构

诊断 hook 安装于原始 DEX 已确认的 `utils.f#a(SearchAllResponse, String, int, ow0.P, int)` 返回点。这里同时读取传入的 protobuf 响应和映射出的列表；因此以下内容确实来自综合搜索主响应，而非由视频详情页或首页列表混入。

| 字段/路径 | 长数字样本 | 正常关键词对照 |
| --- | --- | --- |
| 第 1 页条数 | 20 | 20（含不同卡片类型） |
| 普通视频 oneof / goto | AV / av | AV / av |
| 推荐提示卡 | 本次样本没有 | 未观察到可作为普通视频分类器的提示 |
| cardType / subGoto / moduleId | null | 普通 AV 亦不能靠这些字段区分 |
| isInAlienationArea | false | 不作为相关性标记 |
| AV 的 rcmdReason | protobuf 默认空消息 | AV 同样为空 |
| AV 的 highlightTags | 空列表 | AV 同样为空 |
| annotation | 只有 qv_id | 只有 qv_id |

在第一版诊断测试中，同一长数字查询的第 2 页又返回 20 条 AV / av，页面仍是无关内容。没有发现跨页的专用推荐起点。

第二版响应的 `PaginationReply.next` 是 Base64 编码的 protobuf。样本解码后只含下一页号 2 和查询会话 ID（字段 1、2），没有本次可用的推荐区间标记。文档不保存实际追踪值。请求 `SearchAllRequest` 中也未发现专用的禁用推荐字段；它包含通用 filterMap/fromExtra、排序和 isOrgQuery 等字段，但字段存在不等于服务端支持关闭推荐，不能随便填一个未经验证的参数。

证据文件（均为 git 忽略的本地测试产物）：

- `probe-rare.log`：首批原始卡片与映射列表。
- `probe-pagination.log`：第 2 页 20 条视频。
- `probe-metadata.log`、`probe-metadata-normal.log`：空推荐理由、注解和分页对照。
- `device-probe.xml`、`device-meta.xml`、`device-meta-normal2.xml`：实际查询和页面条目。
- `device-scroll.png`：长数字搜索下翻页后的无关视频。
- `SearchAllRequest.java`、`SearchAllResponse.java`、`SearchVideoCard.java`：protobuf 字段定义。

一次向下滚动的 UIAutomator dump 报 `could not get idle state`；对应 `device-rare-scroll.xml` 是先前文件，不能作为新一页证据。分页结论采用有效 hook 日志及截图。

### 对方案的影响

**当前样本的无关视频是服务端以普通视频卡片返回的搜索结果。** 只拦 `recommend/noresult`、删除 `recommend_tips`/`hot_recommend`，或删除非空 `rcmdReason` 都无法覆盖已经复现的现象。不能因服务端返回普通 av 就认为它语义相关。

建议拆成以下明确能力，而不是声称一个类型过滤器可以解决所有无关视频：

1. **来源标记过滤。** 对未来样本中明确标记的推荐区块精确删除，保留上述分页/状态约束。这对本轮无标记样本不生效。
2. **内容相关性过滤。** 对普通 AV，取得标题、简介、标签、全文命中数据，与本次查询一起判定。数字/编号查询可按完整编号进行严格匹配；自然语言查询需要分词、别名及语义相关性评估。仅“标题不含关键词”不能作为默认删除规则；本地关键词匹配可以作为用户明确选择的严格模式，但应说明会丢失只在字幕、同义表达或视频内容中命中的结果。
3. **不确定结果可恢复。** 建议在每次搜索的列表数据层划分保留/隐藏/待确认集合，提供“查看被过滤条目”和对本次搜索临时关闭过滤。默认对不确定项保留；这意味着保守模式无法保证删除所有无关视频。若用户要求更强过滤，需明确接受严格匹配的召回损失，或加入更强的语义判定。

相关性判定应在后台处理、绑定查询会话和请求代次，返回时丢弃过期判定，避免切换关键词后删错列表。隐藏集合与完整服务端分页集合分开，避免过滤后空列表触发持续自动加载；预取需有明确上限，显示“本页无符合条件的结果”及手动继续搜索入口。普通视频仍需保留服务端原位置和游标。

如果选择语义判定，先建一组人工标注样本：正常命中、同义词、只有简介/字幕命中、无结果兜底、结果耗尽后推荐、夹带广告，以及本次长数字样本。指标同时检查误删率和无关内容残留率。向外部模型发送查询与视频元数据是额外的数据处理设计，本次未发送任何搜索数据到外部模型服务。

本轮已完成：设备复现、主响应卡片取证、分页取证、正常结果对照、方案修订和诊断构建验证。尚未完成：通用内容相关性分类器与生产过滤的真机验收；不能把诊断模块当作过滤实现。

### 3B1B 实际查询补充取证

用户提供真实复现词 `3B1B`，并指出《普罗米修斯》、“今日份释放压抑”和“肉呼呼大腿陪你3小时”。旧版截图确认这些条目出现在综合搜索列表，但旧版没有记录标题，不能逐条关联 protobuf。新版手动输入后通过 `d3-manual.xml` 确认查询及综合结果页，`d3-final.log` 记录了后续分页。

成功关联的两个普通 AV 卡片：

- 第 2 批位置 19：`【はれひな】用肉呼呼的大腿陪你3小时安眠❤超多cosplay角色哄你入睡~`，作者 `B1ue゛`。
- 第 3 批位置 18：`⚡ 今日份释放压抑 ⚡《2》`，作者 `我拉磨贼快`。

二者 `rcmdReason` 都是没有字段的默认 protobuf 消息、`highlightTags=[]`、简介为空、`hasFullText=true`、`fullText.type=6`。前者全文内容为讨论作者及直播合集的评论；后者为“开头那女的脸刷白。 手臂又是黑黑的”。均没有搜索关键词。二进制中对应常量 `SearchVideoFullTextItem.TYPE_COMMENT=6`。

**type=6 只能证明附带评论展示内容，不能证明评论是入选搜索结果的唯一依据。** 正常的 3Blue1Brown 视频（例如“【官方双语】直观视觉（伪）证明三例”）也带 type=6，且评论不含关键词。因此不能直接删除所有 type=6 视频，也不能把它命名为“评论命中过滤”并宣称已验证准确。

正常结果的大量标题没有字面 `3B1B`，而作者是 `3Blue1Brown`。相关性方案至少需要作者及别名匹配；只看标题会误删。当前元数据没有找到可精确区分上述两个无关条目的专用推荐标记。《普罗米修斯》在新版重放的前三批未再次出现，旧截图现象保留，尚未完成其标题与原始卡片关联。

本轮一次点击搜索历史触发现有隐藏默认词功能的 `setSpan(4 ... 4) ends beyond length 0` 崩溃，应用重启回直播首页。未确认页面时进行了滑动，已明确排除该轮日志作为搜索证据；之后改手动输入，确认结果页才分页。后续实现阶段已修复：TextView.setText 的视图层拦截仅用于首页文案，并对 EditText 无条件放行；搜索页默认词仍由 DefaultKeyword 数据源清理，hint 仍可隐藏。避免真实查询被清空后宿主按原词长度设置光标越界。

### 已实现：综合搜索严格过滤

用户选择采用标题＋简介（可取得时）＋用户名的严格过滤。`SearchResultsHook` 在综合搜索 protobuf 映射之前过滤普通 AV；`StrictSearchMatcher` 忽略大小写、全半角和一般标点，保留 `+/#`，现已接入 `com.huaban:jieba-analysis:1.0.2`，中文按 INDEX 模式分词并包含词典内子词，任意一个有效词在独立字段中命中即保留。英文、数字和 +/# 标识保持完整；常见虚词及中文单字噪声排除，无有效分词时按完整查询匹配。查询分词缓存最多 32 项。根据用户后续要求，已移除全部硬编码别名；服务端第一页前 5 个普通 AV 视频不执行匹配，广告和用户卡片不占名额，后续分页不重新豁免。仅用响应已有简介，不额外拉取详情。新开关 `strictSearchResults` 默认关闭，在设置中启用，重新搜索生效。

重建响应只修改卡片列表，并在删减时关闭按原始数量计算的 alienation 折叠，避免错位。服务端分页、位置和查询元数据保留。整页无卡片时插入宿主 `recommend_tips` 提示，避免适配器对空列表直接 return 导致旧结果残留。保留非 AV 卡片，包括广告、用户、相关搜索等。本版本不覆盖其他分类搜索页。

取消别名、增加豁免后的普通构建成功，Java 调用实际编译的匹配器完成 22 项检查，包括第 5/6 条边界及后续分页不重置。此次规则调整未重新安装到设备，下面真机数据是前一版（含别名、无豁免）的验证记录：9.6.0 真机开启开关后，`3B1B` 连续三批分别隐藏 1、6、5 条视频，首屏官方作者的视频保留。长数字查询隐藏 20 条视频、保留 0 张原始卡片；`strict-empty.xml` 确认显示过滤提示且旧查询结果被清除。日志为 `strict-results.log`、`strict-scroll.log`、`strict-empty.log`。后续分页 UIAutomator 因动画无法 idle，未生成 XML，不采用该缺失文件作为证据；分页计数采用 hook 日志。诊断采集默认关闭，本轮生产过滤日志仅记录数量，不记录查询及标题。

首批前 5 条之外，字面严格匹配会丢失仅通过别名相关、只在字幕/评论/视频内容中相关的结果，这是本次用户选择的取舍。仅使用标题/用户名时仍可能留下恰好包含关键词的无关内容，不宣称是语义相关性分类器。

前一轮测试结束时已恢复原安装 APK。随后分词及崩溃修复版已安装在测试设备上：默认词隐藏为 true、严格过滤为 true；新版产物为 `output/search-analysis/segmented/iBiliPlayer-bili-698-npatched.apk`。旧版 strict-final 产物不含本轮修复。

### 诊断构建命令

### 分词与关键词点击修复验收

`jieba-analysis:1.0.2` JAR 为 2,189,961 字节，模块 APK 为 3,884,564 字节。新版宿主 APK 为 269,505,688 字节，相比上一版 267,121,816 字节增加 2,383,872 字节（约 2.38 MB）。无需网络分词服务。

普通构建与 29 项实际匹配器测试通过。新增测试覆盖中文复合词、任意词命中、虚词排除、ASCII 标识不拆分、C++/C# 符号和前五条豁免边界。

真机版本仍为 9.6.0，进程 PID 1635。本轮全程保持 hideSearchPlaceholder=true：点击历史 3B1B 后查询栏正确显示，之后通过 URI 跳转线性代数入门同样正常，进程不变。证据 `seg-history-result.xml` / `.log`、`seg-chinese.xml`。开启 strictSearchResults 后，中文查询真实调用词典，首批隐藏 1 条视频，后续分页隐藏 1 条视频；重新搜索 3B1B 首批隐藏 12 条、保留 8 张卡片，无别名映射。证据 `seg-filter-ready.xml` / `.log`、`seg-pagination.log`、`seg-exempt.xml` / `.log`。

分词初版词典在该设备首次加载约 8186 ms，模型约 71 ms；首次中文过滤有一次加载等待，后续分页未重新加载。词典为进程内单例，查询结果缓存最多 32 项。后续已加入下述后台预加载。

### 启动预加载

综合搜索 Hook 安装完成后，若 strictSearchResults=true，在低优先级后台线程加载分词器；运行中保存开启状态也触发同一入口。AtomicBoolean 防重复，词典与查询共用 Kotlin 同步 lazy 单例，不建立第二份词典，不同步阻塞启动线程。关闭过滤时不启动预加载；加载完成后关闭过滤不会释放词典。

预加载失败记录错误并解除触发标记，宿主启动继续。实际搜索仍按原来的词典加载和错误兜底执行；若搜索早于预加载完成，可能等待剩余加载时间，因此本方案转移了加载时机，并非消除了词典加载成本。

构建及 29 项匹配回归通过；额外测试重复触发 10 次预加载，仅发生一次完成回调，且回调不在调用线程执行。

真机冷启动 PID 13725：Hook 在主线程 15:36:34.100 安装，词典在后台线程 24828 于 15:36:43.412 完成预加载（9309 ms）。随后首次中文搜索在 15:37:14.437 执行过滤，未重复加载词典，结果正常。证据 `preload-ready.log`、`preload-search.log` / `.xml`。2 秒处截图仍为宿主启动画面，仅用于记录启动阶段，不据此声称首页已渲染或测得启动性能改善。

预加载版留在设备，产物 `output/search-analysis/preloaded/iBiliPlayer-bili-698-npatched.apk`。加载成本移到启动后台，首个搜索早于加载完成时仍可能等待，不保证零延迟。

本轮新版留在测试设备上，过滤开关已开启，默认词隐藏保持开启。普通构建的诊断采集仍关闭。实现范围仍仅综合搜索中的普通 AV 卡片。

### 诊断构建操作

### 前八条验证（历史规则，已被前十验证／前五豁免替代）

取消无条件前五条豁免，使用 `SearchResultGate`：前八个普通 AV 中至少一条通过分词匹配才放行，前八条豁免，其余继续匹配；前八条全不匹配则阻断整个列表并清空服务端 next 游标。非视频卡片不消耗验证名额，查询被阻断时也隐藏，防止广告单独成为窗口。

不足八条且还可翻页时，不在首次结果中展示待验证卡片。按搜索数据加载器的 P 实例身份（弱引用，使用 ===，不依赖可变字段的 equals/hashCode）及查询文本保存会话，下一页补足后再决定；验证通过时按原顺序补回暂存卡片，失败则丢弃。结果耗尽时按实际条数判断。第一页刷新或改变查询会重置会话；分页重复请求使用缓存决定，不重复累计。旧的等待提示在宿主累积列表得到视频或阻断提示后清理。

编译、20 项分词匹配回归及后台单次预加载测试通过，12 项会话测试通过，包括第八条/第九条边界、7+1 跨页、耗尽短列表、重复页、非视频卡片不占名额及阻断后的后续页。

9.6.0 真机 PID 20771：3B1B 验证为 ALLOWED，首批 20 张卡片输出 12 张；后续三个批次分别输出 3、1、1 张卡片，豁免未重置。长数字 731829460517328 验证为 BLOCKED，20 张卡片输出 0，UI 仅显示阻断提示，旧查询结果清除。继续滑动后仍保持阻断，新搜索 3B1B 重新进入 ALLOWED。证据 `eight-valid.xml` / `.log`、`eight-pagination.log`、`eight-blocked.xml` / `.log`、`eight-blocked-after-scroll.log`、`eight-reset.log`。清除 next 防止正常下一页加载；宿主仍可能重复调用映射器，重复回调同样阻断，不据此声称所有网络重试完全停止。

首批七条的情况采用独立会话测试模拟，未宣称已在服务端真实返回七条的设备样本中验收。当前实测每批为二十张卡片，不等于二十条普通视频。

最新版已留在测试设备，默认词隐藏和过滤均开启，产物为 `output/search-analysis/gated-eight-final/iBiliPlayer-bili-698-npatched.apk`。普通构建 SEARCH_DIAGNOSTICS=false。早期 gated-eight 包已被后续对象身份关联修复版本替代，不作为发布产物。

### 诊断复现命令

### 当前规则：第一页前十验证，前五豁免

验证数量和豁免数量独立：仅检查第一页前 10 个普通 AV 的关键词匹配；不足 10 条则检查第一页实际全部视频，不等待下一页。范围内至少一条匹配才批准整次搜索，且仅全局前 5 个普通视频豁免，后面一律关键词匹配。第一页验证全不匹配则取消所有豁免，屏蔽整个列表、清空 next，并保持后续页阻断。

删除跨页暂存、PENDING 状态及等待提示清理 Hook。匹配和后台预加载回归通过，14 项新版会话测试通过：第 10 条命中可批准、第 11 条命中不能批准；批准后第 6～9 条仍需匹配；短首页立即判断；非视频卡片不消耗两个计数；后续页不重新验证或重置豁免。

真机修正版 PID 25237：“线性代数入门” ALLOWED，20 张卡片输出 14；长数字查询 BLOCKED，输出 0 且仅显示第一页前十条无匹配提示。本次 3B1B 也 BLOCKED，表明放宽到前十条仍不能保证别名查询通过，需保留这一字面规则局限。证据 `ten-five-valid.log`、`ten-five-blocked.xml` / `.log`、`ten-five-chinese.xml` / `.log`。设备已更新并保留修正版 `output/search-analysis/gated-ten-five/iBiliPlayer-bili-698-npatched.apk`，之前八条版不再是当前版本。

### 诊断命令参考

普通构建的 `BuildConfig.SEARCH_DIAGNOSTICS` 为 false，不安装诊断 hook。需要再次采集时：

```powershell
.\gradlew.bat :app:assembleDebug -PsearchDiagnostics=true --console=plain
java -cp 'tools/npatch.jar;tools/build' NPatchLauncher original/iBiliPlayer-bili.apk -m app/build/outputs/apk/debug/app-debug.apk -o output/search-analysis/diagnostic -f -l 0
```

必须先等编译成功再打包。查询响应和分页为 protobuf，不能当成 JSON 字段猜测。诊断不修改返回结果；只在明确诊断构建中采集结构元数据，当前源码会屏蔽注解中的追踪 ID 值。取证结束恢复原安装 APK，普通构建也已验证成功。

本机 Java 曾因默认临时目录的 Unix socket 路径报 `Unable to establish loopback connection`；本次使用进程级 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=E:/WorkBench/MiniProjects/BiliDetox/output` 完成构建，未修改系统级 Java 设置。
