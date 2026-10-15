# 接入与扩展

本附属依赖 BS3D 的随身容器树、模型资产、材质规则和统一路由。不会复制 `bbmodel`、玩家渲染器、物品注册表或重量逻辑。MIT 许可沿用依赖仓库的版权声明。

## 模型和物品

新增背包仍在 BS3D 注册，并由它管理 `config/bs3d/config.toml`、模型和可选开合资源。简介使用 BS3D 物品元数据；重量字段由重量附属消费，本附属不解释它们。

玩家模型使用 `WornPreview`；内景使用 `InteriorRenderer`、`BagAnimation` 和 `LayerRangeSlider`。颜色来自 Framework 当前主题，无独立硬编码配色。

## 扩展边界

原版支持列表位于 `VanillaMenus`。新增菜单首先确认其真实 `ScreenHandler` 槽位和权限语义；不要通过创建客户端容器冒充服务器已经打开的菜单。第三方菜单当前没有正式注册 API，待实际接入需求确定后再增加。

BS3D 新增 `InventoryAccess.previewCursor` 和 `InventoryAccess.exchangeCursor`。参数为真实 `ServerPlayerEntity`、`CursorTransfer.Request` 和期望光标指纹；调用必须发生在服务器主线程，调用者负责把请求绑定到当前页面并提供幂等与取消。预览只返回副本；提交方法重新预览，不能直接提交旧候选。

`CursorTransfer.Request.take(revision, item)` 与 `store(revision, container, cell, orientation)` 只交换随身容器与原版光标。计时和页面身份属于附属，不写进 BS3D 保存格式。不要修改 `Candidate` 后自行覆盖真实玩家库存。

公开 API 尚未冻结。配套源码版本、已跑场景与限制见[验证记录](Validation.md)，协议字段见[通信契约](Protocol.md)。
