# 接入与扩展

本附属依赖 BS3D 的随身容器树、模型资产、材质规则和统一路由。不会复制 `bbmodel`、玩家渲染器、物品注册表或重量逻辑。MIT 许可沿用依赖仓库的版权声明。

## 模型和物品

新增背包仍在 BS3D 注册，并由它管理 `config/bs3d/config.toml`、模型和可选开合资源。简介使用 BS3D 物品元数据；重量字段由重量附属消费，本附属不解释它们。

玩家模型使用 `WornPreview`；内景使用 `InteriorRenderer`、`BagAnimation` 和 `LayerRangeSlider`。颜色来自 Framework 当前主题，无独立硬编码配色。

## 扩展边界

原版支持列表位于 `VanillaMenus`。新增菜单首先确认其真实 `AbstractContainerMenu` 槽位和权限语义；不要通过创建客户端容器冒充服务器已经打开的菜单。第三方菜单当前没有正式注册 API，待实际接入需求确定后再增加。

`InventoryAccess.previewCursor` 和 `InventoryAccess.exchangeCursor` 用于随身容器与原版光标交换。参数为真实 `ServerPlayer`、`CursorTransfer.Request` 和期望光标指纹；调用必须发生在服务器主线程，调用者负责当前页面身份和幂等。预览只返回副本；提交时重新读取真实库存，不能直接提交旧候选。

完整拖放使用 `InventoryAccess.cursorTransaction`：先 `exchange` 组合来源与落点，原版点击全部成功且光标为空后再 `commit`。地面候选在此之前仅存在于内存，最终复用 BS3D 玩家与地面目录的日志提交；失败恢复菜单、玩家和业务快照。不要修改 `Candidate` 后自行覆盖真实玩家库存。

## 玩家区与页面布局接入点

- `PlayerInventoryRows.setVisibleRows(int)`：登记玩家主背包可见行数（1–3，默认 3），只在模组初始化时调用。已适配的工作站与容器页按此行数裁接原版背景、隐藏多余行，并把快捷栏接到最后一条可见行下方；未适配菜单保持原样。这里只改显示，被隐藏槽位的权限由登记行数的附属在服务端负责，例如 Pocket 附属。
- `StationHost.stationLayout()`：页面可以提供自己的人物与内景区域，默认放在原版工作站两侧。
- `StationHost.stationSlotVisible(Slot)`：页面可以隐藏部分原版槽位，被隐藏的槽位不绘制，也不参与悬停与点击命中。
- `SlotPosition`：只改原版槽位坐标的访问器，不替换带装备与绑定诅咒检查的原版槽对象。

以下接入点供带页签或客户端专有菜单的页面使用，目前只有创造模式物品栏用到，默认值保持原有行为：

- `StationHost.stationSupported()`：是否创建场景，默认按 `VanillaMenus` 适配表判断服务器菜单。
- `StationHost.stationActive()`：当前页签是否显示场景，默认始终显示。
- `StationHost.stationMenuSlot(Slot)`：把页面槽换成服务器菜单里的真实槽，默认就是页面槽本身；返回 `null` 表示该槽只存在于客户端页面（如创造页删除格），场景不对它提交事务。
- `StationHost.stationSharedCursor()`：光标是否由服务器同步，默认是。返回 `false` 时光标只在客户端：携物期间不发事务（包括建立场景），以免指纹被拒后的全量同步清掉本地光标。
- `StationHost.stationClick(Slot)`：以页面自身的原版点击入口点一次页面槽，由 `AbstractContainerScreen` 混入实现，页面专有的点击规则（如创造写入）随之生效。

## 背包事务

`InventoryAccess.openScene` 只为当前连接授权附近可操作的地面根，不接受客户端位置或扩大任意 UUID 的访问范围。页面关闭使用 BS3D 的关闭消息释放租约。`Request.autoStore` 与手动 `store` 共用整树校验；自动随身目标 `carried` 不包含地面容器。

公开 API 尚未冻结。配套源码版本、已跑场景与限制见[验证记录](Validation.md)，协议字段见[通信契约](Protocol.md)。
