# 验证记录

2026-10-15，未发布源码实现，API 未冻结。Framework 基线为 `b2f6e3021c40f052702d48301408b333b3ed378f`；配套 BS3D 为 `8f2f99c82545abeca2802f2acbf609bc105c087e`。

## 已验证

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
