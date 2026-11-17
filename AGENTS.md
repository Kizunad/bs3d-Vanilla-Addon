# 仓库协作约定

- 简体中文交流、文档和关键方法内部注释；公开名称使用英文。
- 先读再改，按读取、校验、执行、同步的顺序编写；完整花括号，避免复合条件和魔法数。
- 原版工作站的配方、经验、燃料和槽位规则由原版 AbstractContainerMenu 处理，不另写配方引擎。
- 物品只来自服务端真实库存；附属意图使用 Protobuf，经 Framework 统一路由发送。
- 单机复用服务器源码；BS3D 负责容器树和物品身份，附属不复制库存。
- UI 复用 BS3D 场景及 Framework 主题；不额外渲染原版 HUD。
- 接入文档唯一来源为 docs/wiki/，明确已实现、已验证和未发布边界。
- 提交前运行 python3 scripts/wiki.py check；测试夹具不进入生产 JAR。
- 保留用户修改，不强推，不擅自提交或发布。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
