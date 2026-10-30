# 验证记录

## 2026-10-30：创造模式「物品栏」页签接入

为 Pocket 附属的创造页三维人物新增五个接入点：`StationHost.stationSupported`、`stationActive`、`stationMenuSlot`、`stationSharedCursor` 和 `stationClick`。另外新增创造页混入，以及 `CreativeSlot` 的真实槽访问器。说明见[接入与扩展](Extension-Guide.md)和[交互与支持范围](Interaction.md)。默认值保持原有行为：普通页面的页面槽就是菜单槽，光标由服务器同步。业务协议仍是 **v2**。

创造页「物品栏」页签上的行为：

- 槽位、模型和内景之间的拖动沿用即时事务，服务器侧执行的是玩家菜单。
- 本地光标携物时，点人物部位改为点击隐藏的原版装备槽，走原版创造写入。
- 点背包模型或内景只在本地提示；在场景内松开不会被当作丢出；这期间也不发送场景请求。

`StationPanel` 还有一处对所有页面生效：原地点一下槽位，改为经页面自身的 `onMouseClick` 发出。普通页面最后调用的仍是同一个 `clickSlot`。

Java 17 离线 `test build :client:compileSmokeJava :server:compileSmokeJava` 通过，**5 项测试**无失败或跳过。日志为远程会话仓库的 `handoff/logs/bs3d-pocket-creative-vanilla-test-build.log`。

以默认 3 行重跑本仓库的完整联调，专服和单机都通过：

- `build/smoke/ffd15cedf1a3-dedicated/client/vanilla-ok.txt`
- `build/smoke/ffd15cedf1a3-integrated/client/vanilla-ok.txt`

日志为 `handoff/logs/bs3d-pocket-creative-vanilla-smoke.log`。创造页的真实客户端联调由 Pocket 附属覆盖：三维场景与紧凑窗口、头盔的事务穿脱、本地光标点模型穿戴、伪造写入被拒。记录见该附属的验证记录。

## 2026-10-30：玩家区可见行数与页面布局接入点

为 Pocket 附属新增了四个接入点：`PlayerInventoryRows`、`StationHost.stationLayout`、`StationHost.stationSlotVisible` 和 `SlotPosition`，说明见[接入与扩展](Extension-Guide.md)。默认行数为 3，所以不安装登记行数的附属时，玩家区和场景布局都不变。`StationPanel` 另有三处小调整，对所有页面生效：原版槽位上的右键交还原版处理（分堆）；人物区可以按住右键拖动旋转；拒绝原因改为贴在内景区域底边显示。业务协议仍是 **v2**。

Java 17 离线 `test build :client:compileSmokeJava :server:compileSmokeJava` 通过，**5 项测试**无失败或跳过。日志为远程会话仓库的 `handoff/logs/bs3d-pocket-vanilla-test-build.log`。

以默认 3 行重跑完整联调，专服和单机都通过：

- `build/smoke/89fefcf1b1b4-dedicated/client/vanilla-ok.txt`
- `build/smoke/89fefcf1b1b4-integrated/client/vanilla-ok.txt`

日志为 `handoff/logs/bs3d-pocket-vanilla-smoke.log`。重跑前，地面容器步骤在机器高负载时失败过两次（`handoff/logs/bs3d-pocket-vanilla-smoke-rerun.log`）。原因是开盖动画按帧推进，Xvfb 帧率低时固定 30 tick 不够。探针现在等内景实际渲染出来再拖动，产品代码未改。

可见行数设为 1 时的箱子页、紧凑 E 页和创造物品栏页签，由 Pocket 附属的真实客户端联调覆盖，记录见该附属的验证记录。

## 2026-10-30：即时拖放与双向快捷搬运

当前为未发布源码，业务协议 **v2**，配套 BS3D 业务协议 **v7**，两端须同步更新。Java 17 离线 `test build :client:compileSmokeJava :server:compileSmokeJava` 通过，附属客户端 **3 项**、服务端 **2 项**，共 **5 项测试**，无失败或跳过；配套 BS3D **130 项**、PA 摆动附属 **3 项**测试也通过。

使用最终 BS3D 生产 JAR，真实 Fabric 专服和 Xvfb 单机联调均通过：

- `build/smoke/ef7d1842ac61-dedicated/client/vanilla-ok.txt`
- `build/smoke/ef7d1842ac61-integrated/client/vanilla-ok.txt`

覆盖模型双手和内景拖放、原版合成扣料与产物、副手、即时拒绝及提交、幂等重放、伪造菜单／指纹／槽位／字段拒绝、熔炉、箱子、合盖模型自动放置、Shift 双向搬运、E 玩家页及地面容器；单机另验证同世界重启和已保存内容。成功回执无需客户端补点击，失败保留原库存。旧计时非零字段经真实 Protobuf 解码后被拒绝。

构建与烟测日志分别为远程会话仓库的 `handoff/logs/bs3d-instant-vanilla-test-build.log`、`handoff/logs/bs3d-instant-vanilla-smoke.log`。两端生产 JAR 不含 smoke 夹具，专服 JAR 不含客户端渲染或 owo 类；客户端与服务端 mixin 所引用的 refmap 均存在，`insertItem` 已映射到 intermediary。该轮 E 页验证的是原版 `InventoryScreen`；Pocket 紧凑 E 页的联调见上一节。本次未启动 Windows Native。

## 历史记录：2026-10-15

2026-10-15，未发布源码实现，API 未冻结。Framework 基线为 `b2f6e3021c40f052702d48301408b333b3ed378f`；配套 BS3D 为 `8f2f99c82545abeca2802f2acbf609bc105c087e`。

以下计时与取消行为仅说明当时版本。

### 已验证

- Java 17 `test build`：BS3D 共 100 项测试，附属 4 项，全部零失败。
- BS3D 的 7 项新增测试覆盖光标预览不写入、NBT 保留、取放守恒、过期修订、占格越界、占用光标、外部身份、嵌套自包含及容量不足。
- 附属测试覆盖场景布局退让，以及真实 Framework 路由拒绝把 S2C 状态当作 C2S 请求。
- 实际 Fabric 独服和单机均验证模型手部与内景拖入工作台、原版扣料及产物、副手接收、背包计时取消与完成、请求重放、伪造页面/指纹/槽位/未知字段拒绝、熔炉完成冶炼与取出、箱子物品存回背包。
- 单机还验证同世界关闭重开，背包内的四块木板和五块石头及修订号保持不变。
- README 截图来自真实客户端帧缓冲。Wiki 链接检查、空白检查通过；生产 JAR 不含 smoke，服务端 JAR 不含客户端渲染和 owo 类。

本次通过标记：

- `build/smoke/8a5bb36710a8-dedicated/client/vanilla-ok.txt`
- `build/smoke/768835de9026-integrated/client/vanilla-ok.txt`

## 验证边界

工作台、熔炉和箱子有真实端到端验收。其他列入支持表的原版菜单共用适配器并已编译，但尚未逐个手动操作验收；不宣称所有特殊控件都已穷尽验证。Windows Native 未启动；未发布正式 Release 或 Maven 制品，也未单独发布 GitHub Wiki。

光标交换沿用原版关闭与保存机制；没有进程崩溃时玩家与方块跨文件原子性的保证。配方书展开及狭窄分辨率的退让规则见[交互说明](Interaction.md)，身份和指纹约定见[通信契约](Protocol.md)。

## 重跑联调

`scripts/smoke.py` 在新的隔离目录启动 Linux Xvfb 客户端和本地专服，或单机内置服务器；不启动 Windows Native。测试夹具仅存在于 `smoke` 源集，生产 JAR 不包含夹具入口。

```bash
python3 scripts/smoke.py --mode both
```

参数 `--mode dedicated` / `integrated` 选择环境；`--port` 变更本地验收端口。脚本只管理自己创建的进程组，不处理已有游戏或服务器。日志、截图与通过标记留在 `build/smoke/`。运行期间不要在同一工作区执行另一轮构建，以免重写正在加载的类文件。
