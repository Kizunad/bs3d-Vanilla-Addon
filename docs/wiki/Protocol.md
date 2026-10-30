# 通信契约

业务源为 `shared/src/main/proto/vanilla.proto`，包 `kizuna.bs3d.vanilla.v1`，业务版本 2。外层复用 Framework wire v2，模块 `bs3d_vanilla:menus`，不注册额外网络通道。

| 消息 | 方向 | 内容 |
| --- | --- | --- |
| `bs3d_vanilla:subscribe` | C2S | 空 `SubscribeRequest`，只订阅自己的已认证连接 |
| `bs3d_vanilla:action` | C2S | `ActionRequest`，引用当前菜单与真实来源，包含来源与最终落点 |
| `bs3d_vanilla:state` | S2C | `MenuState`，页面身份与终态回执 |

三项能力均为可选；服务端未安装时仍可使用基础库存和原版菜单。

## 请求身份

`menu` 是服务端为每次原版页面生成的 UUID，与可循环复用的原版 `sync_id` 同时校验。`operation` 是客户端生成的 UUID，用于当前页面内的幂等收据，不是认证凭据。服务端连接绑定玩家，协议不能指定另一个玩家或远程方块位置。

`cursor_fingerprint` 是 BS3D `StackCodec.fingerprint(ItemStack)`：非空栈使用规范 NBT 的 SHA-256，空栈使用空字节序列的 SHA-256。只用于内容对拍，不能用指纹创建物品。`inventory_revision` 为 BS3D 保存修订号，执行背包转移前校验。

`oneof action`：

- `transfer`：原版槽位/模型部位/内景/光标来源，以及原版槽位/部位/容器/自动原版库存落点。模型使用 auto_place；随身快捷目标为 carried。
- `open_scene`：当前页面首次显示场景时申请附近地面根的访问租约，不携带容器 UUID 或世界坐标。关闭页面释放租约。
- `take_item`：随身树中的物品实例 UUID，取出整栈到空光标。
- `store_cursor`：容器 UUID、X/Y/Z 格坐标及 0..23 朝向，存入整个真实光标栈。
- `player_slot`：当前主手、副手或盔甲的 PlayerInventory 槽号与栈指纹。执行原版左键点击语义，不绕过装备规则。
- `cancel_operation`：兼容空操作，即时事务不存在取消阶段。

服务端拒绝未知字段、失效页面、无效身份、错误指纹、非身体槽位、不可用玩家及容器；背包体积、嵌套、重复实例身份由 BS3D 重新验证。

每个页面缓存最近 128 个操作收据。相同请求重发只返回原收据，不再次取物；同号不同参数拒绝。跨页面请求因 `menu` 改变而失效。客户端不能把 S2C 状态作为 C2S 请求提交。

## 状态与原版同步

`CLOSED` 表示没有适配菜单；玩家或工作站菜单为 READY。请求同一调用内完成，返回 COMPLETED 或 REJECTED。旧 RUNNING=2 和 MenuState 字段号 5/6（elapsed_ticks/duration_ticks）保留不复用，非零旧计时或未知阶段解码拒绝。

拖放请求携带来源和最终落点，服务端先交换 BS3D 与唯一原版光标，再执行一次原版点击，失败恢复菜单、玩家和 BS3D 库存。地面栈在完整落点成功前只修改候选，最终空光标时再通过 BS3D 日志同时提交玩家与地面目录；不允许把地面物品单独取到待保存光标。自动取回复用原版 insertItem；成功后统一同步槽位和终态，客户端不再等待回执补发点击。来源指纹、页面、业务修订和幂等收据保持有效。

原版槽位、配方书、燃烧进度和按钮继续走 Minecraft 协议。保存沿用原版玩家与方块生命周期，不声明玩家存档与世界方块在进程崩溃时具有跨文件原子提交能力。BS3D 独立即时转移的玩家原子保存及地面日志格式不变。
