# 通信契约

业务源为 `shared/src/main/proto/vanilla.proto`，包 `kizuna.bs3d.vanilla.v1`，业务版本 1。外层复用 Framework wire v2，模块 `bs3d_vanilla:menus`，不注册额外网络通道。

| 消息 | 方向 | 内容 |
| --- | --- | --- |
| `bs3d_vanilla:subscribe` | C2S | 空 `SubscribeRequest`，只订阅自己的已认证连接 |
| `bs3d_vanilla:action` | C2S | `ActionRequest`，引用当前菜单与真实来源，或取消指定操作 |
| `bs3d_vanilla:state` | S2C | `MenuState`，页面身份、操作状态及以服务器 tick 为单位的进度 |

三项能力均为可选；服务端未安装时仍可使用基础库存和原版菜单。

## 请求身份

`menu` 是服务端为每次原版页面生成的 UUID，与可循环复用的原版 `sync_id` 同时校验。`operation` 是客户端生成的 UUID，用于当前页面内的幂等收据，不是认证凭据。服务端连接绑定玩家，协议不能指定另一个玩家或远程方块位置。

`cursor_fingerprint` 是 BS3D `StackCodec.fingerprint(ItemStack)`：非空栈使用规范 NBT 的 SHA-256，空栈使用空字节序列的 SHA-256。只用于内容对拍，不能用指纹创建物品。`inventory_revision` 为 BS3D 保存修订号，开始与完成背包转移都校验。

`oneof action`：

- `take_item`：随身树中的物品实例 UUID，取出整栈到空光标。
- `store_cursor`：容器 UUID、X/Y/Z 格坐标及 0..23 朝向，存入整个真实光标栈。
- `player_slot`：当前主手、副手或盔甲的 PlayerInventory 槽号与栈指纹。执行原版左键点击语义，不绕过装备规则。
- `cancel_operation`：取消当前连接指定的未完成操作。

服务端拒绝未知字段、失效页面、无效身份、错误指纹、非身体槽位、不可用玩家及容器；背包体积、嵌套、重复实例身份由 BS3D 重新验证。

每个页面缓存最近 128 个操作收据。相同请求重发只返回原收据，不再次取物；同号不同参数拒绝。跨页面请求因 `menu` 改变而失效。客户端不能把 S2C 状态作为 C2S 请求提交。

## 状态与原版同步

`CLOSED` 表示没有适配菜单；打开后为 `READY`。背包交换先 `RUNNING`，完成为 `COMPLETED`；验证失败为 `REJECTED`，已开始动作被中断为 `CANCELLED`。开始和推进期间都不改变物品；完成时在服务器主线程更新背包与唯一原版光标栈，再发送原版全量同步和完成事件。

原版槽位、光标、配方书、燃烧进度及工作站按钮继续使用 Minecraft 自己的协议。Protobuf 只承载附属操作和进度，不复制一套原版库存快照。客户端看到完成后才能提交预先选定的原版槽位点击。

保存沿用原版玩家与方块生命周期。光标由原版关闭菜单时归还；不声明玩家存档与世界方块在进程崩溃时具有跨文件原子提交能力。BS3D 原有独立计时转移和地面日志格式不变。
