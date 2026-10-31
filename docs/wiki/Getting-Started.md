# 安装与构建

需要 Minecraft 26.2、Java 25、Fabric Loader 0.20.1、Fabric API 0.161.0+26.2、owo 0.13.1+26.2，以及包含光标交换接口的 BS3D 和 Framework。三个项目均使用 `0.1.0-SNAPSHOT`，仅凭文件名无法区分源码版本。

配套源码版本记录在[验证记录](Validation.md)。Framework 与 BS3D 以组合构建接入，随本仓构建先行编译，在本仓库执行：

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk25 test build
python3 scripts/wiki.py check
```

构建还需要相邻的 `Kizuna-Meridian-Mod-Framework`：`settings.gradle` 应用其 `scripts/workspace.gradle`，上述版本与 Gradle 9.8.0、Fabric Loom 1.19.3 都取自其版本目录 `scripts/libs.versions.toml`。默认依赖目录为相邻的 `../Kizuna-Inventory-UI-Framework` 和 `../backpack-and-storage-3D`，其他布局用 `-Pkizuna.dir.inventory-ui=…`、`-Pkizuna.dir.bs3d=…` 指定。Gradle 须在 Java 25 上运行，产物以 Java 25 为目标（Minecraft 26.2 的要求）。26.2 不混淆，直接用官方名编译，没有映射与 refmap，安装产物就是 `jar` 任务输出。Gradle Wrapper 已提交，不需要全局安装 Gradle；首次构建需要网络下载依赖。

产物：

| 产物 | 用途 |
| --- | --- |
| `client/build/libs/bs3d-vanilla-addon-client-0.1.0-SNAPSHOT.jar` | 客户端、单机；与 Framework 客户端、BS3D 客户端及 owo-lib 一起安装 |
| `server/build/libs/bs3d-vanilla-addon-server-0.1.0-SNAPSHOT.jar` | 专用服务器；与 Framework 服务端、BS3D 服务端一起安装，无客户端渲染依赖 |
| `build/distributions/bs3d-vanilla-addon-0.1.0-SNAPSHOT-proto.zip` | `.proto` 与描述符，供协议接入 |

同一个实例只安装对应端的一份附属 JAR。客户端 JAR 已包含单机逻辑服务器，不再叠放 server JAR。第三方接入应固定三仓库提交，不把 SNAPSHOT 当作稳定二进制兼容保证。

打开工作站和箱子的方式仍是原版右键。无需命令注册或另开模拟库存。服务器缺少本附属时，客户端保留原版界面；协议协商状态可以在 Framework 日志中查看。
