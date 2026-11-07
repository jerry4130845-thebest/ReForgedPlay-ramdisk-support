# ReForgedPlay：1.21.1 迁移与可配置存储实施方案

## 最终交付状态（2026-11-07，最新结论）
- 本次批准范围的NeoForge1.21.1/Java21迁移及六根自定义路径实现完成。用户确认的5处Shadow字段名修正后，独立D重新执行mc_gradle clean build，带-Djava.io.tmpdir=C:\Temp，**BUILD SUCCESSFUL in49s，9 tasks全部executed**；daemon-5876.out.log:194–195，退出status0。父再次读取该日志及XML/生产类/文件摘要核实一致。
- 原有SPTimelineTest：**41pass，0fail，0error，0skip**，本轮XML timestamp2026-11-07T18:13:46；未改测试。两条旧field remap警告消失；仍有2条已有Keyboard候选警告及14条编译弃用警告、旧Loom/Gradle提示，未擅改范围外代码。
- 最终非dev制品：[reforgedplaymod-1.21.1-0.3.jar](build/libs/reforgedplaymod-1.21.1-0.3.jar)，18591070bytes，SHA256 `fb9fdf6943ff1bcb5085e1b25d6e3b03dc54e22d0c60824824fd8a6bce6d76f6`。生产Shadow及3个getfield均正确使用sectionRenderDispatcher/sectionOcclusionGraph，原intermediary/named错误名不在该class字节中，major65；旧静态阻塞解除。
- 本轮重新核归档版本0.3/2.6.18、MC[1.21.1,1.21.2)、NeoForge[21.1.249,21.2)、10mixins声明、pack34；其它完整归档/依赖/主链证据复用前一D。非Fabric通用包，不使用dev-shadow；实际refmap数仍0（直接remap），未捏造资源存在。
- **只完成构建、现有测试及静态核验，未跑游戏、跨盘/ImDisk或故障实验，不是完整游戏兼容认证。** 用户明确保留EXR缺lwjgl.jar的旧问题（不能宣称EXR可用）与YouTube转码temp例外；其余超时保留/provider保留/恢复扫描/进程锁/MP4/Iris范围，以及原有restriction未实现和粒子零四元数问题，均见后文，不隐瞒或擅修。
- 独立D6a4310ea-0c59-4202-bbd9-4d802255b30b正式完成并停止全部写入/构建；本D零手工文件修改。最终源码diff --check通过，src/test、YoutubeUploader、EXRWriter无diff。无部署配置修改、包安装、系统盘/RAM盘设置或Git写操作。下面旧阶段记录保留作历史，不覆盖本节最终状态。

## 配置使用说明（已构建，游戏与RAM盘实测尚未执行）
- 退出游戏后编辑游戏目录内`config/replaymod.json`，把六个字段合并进现有`advanced`对象；不要用示例覆盖其它设置。均为字符串；相对路径以游戏目录为基准，首次业务读取冻结六根，修改后需重启。所有根按需创建；不会安装/挂载RAM盘、硬编码盘符、修改全局java.io.tmpdir或自动搬迁既有文件。
- `tempPath`：普通临时目录，空字符串继承启动时JVM临时目录；`cachePath`：缓存，默认`./.replay_cache/`；`recordingTempPath`：活动录制/恢复工作根，空值为成品根下`recording`；`recordingPath`：成品，默认`./replay_recordings/`；`rawPath`：原始备份，空值为成品根下`raw`；`renderPath`：默认视频输出，默认`./replay_videos/`，既有界面明确选择的输出文件仍优先。
- 示例仅表示分盘方法，X和D都必须替换为用户自己的已挂载RAM卷与持久卷；这些路径未部署或实测：
```json
{
  "advanced": {
    "tempPath": "X:/ReplayMod/temp",
    "cachePath": "X:/ReplayMod/cache",
    "recordingTempPath": "X:/ReplayMod/recording-work",
    "recordingPath": "D:/ReplayMod/recordings",
    "rawPath": "D:/ReplayMod/raw",
    "renderPath": "D:/ReplayMod/videos"
  }
}
```
- 目录不要相同/互相包含；允许成品根包含彼此独立的工作/raw子目录，危险根、链接/junction等会被拒绝。目标卷发布/替换的自有`.part`仍必须在目标卷，不是所有中间文件都强制走tempPath。盘离线/空间不足/碰撞会报错，不静默改盘或覆盖目标。
- 更稳妥的初始用法是只把temp/cache放RAM盘，recordingTempPath放持久卷。工作根也支持RAM盘，但未完成录制和恢复材料掉盘/断电后可能永久丢失。10秒等待超时/中断保留句柄和锁，不自动晚到收尾，可能需要重启恢复；重新启动必须仍能访问原工作目录。更换路径不自动搬迁旧录制，旧自定义目录需自行保留/找回。
- 边界：YouTube非MP4转码临时文件经用户明确决定仍在原范围外；原项目EXR缺少loader要求的内嵌lwjgl.jar，用户已明确选择仅记录限制、不追加修改，不能宣称EXR导出可用。交给游戏资源包provider的临时目录保守保留，确认所有相关进程结束后才可人工清理日志中指出的自有目录，不能清共享temp根。原有时间线测试不能替代存储故障、ImDisk或游戏端到端测试。

## 目标与现状
- 项目根目录：`C:/Users/zhenz/ReForgedPlay`；目标为 Minecraft 1.21.1 / NeoForge / Java 21，不做 GUI、驱动集成或磁盘安装配置。
- 成功标准：目标版本可构建，录制/回放/导出实际验证；temp、cache、录制工作 recording、成品 record、raw 备份、video 输出分别配置，保存与读取一致。
- 本轮只读取了 [build.gradle](build.gradle) 与 [mods.toml](src/main/resources/META-INF/mods.toml)，仅新增本方案；未检索、构建、安装、运行游戏或修改 Git。
- 已读取确认：Architectury Loom 1.4-SNAPSHOT、Java 17、Yarn layered + 本地补丁、Shadow 8.1.1；旧元数据声明 MC 1.20.4。
- 委派提供的已核实基线：MC 1.20.4 / Yarn 1.20.4+build.1 / NeoForge 20.4.234，wrapper 8.6；本轮不重复研究。
- 时代决策：从 Java 17 的 1.20.4 迁至 Java 21 的 1.21.1，元数据迁移为 `META-INF/neoforge.mods.toml`。
- 优先保留现有 Loom/Yarn 命名空间，不盲切 ModDevGradle/Mojmap，不重建整个工程或手写 wrapper。
- 候选组合：NeoForge 21.1.249、Yarn 1.21.1+build.3、Architectury Loom 1.7.435；发布存在已核实，三者兼容性 UNVERIFIED。
- 本机 Java 21、`C:/Temp` 已存在；RAM disk 是普通已挂载目录，不硬编码 `R:`，不改变全局 `java.io.tmpdir`。

## 已获批范围与文件归属
- 下表 B 的 6 个既有路径 + 1 个新增路径是第一阶段精确写入白名单；旧元数据允许迁移/移除，不能并存造成歧义。
- C 的 13 个既有文件属于此前获批存储实现范围，但 B 不得编辑；C 接手前须核对诊断及范围，新增/范围外文件必须先由父代理另获用户批准。
- 当前没有授权新增/修改测试、部署 JSON、Fabric 元数据、其他源码或 vendored jar；D 默认只读审查与运行验证，修复也受同一授权限制。
| 文件或范围 | 归属 | 工作边界 |
| --- | --- | --- |
| [build.gradle](build.gradle) | B | Java 21、Loom 与依赖适配、资源展开目标；不借机删除功能 |
| [gradle.properties](gradle.properties) | B | 更新目标 MC / Yarn / NeoForge 版本 |
| [settings.gradle](settings.gradle) | B | 必要的插件/依赖仓库解析调整 |
| [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) | B | 仅按真实插件要求调整 wrapper 版本；不手写 wrapper |
| [yarnpatch.tiny](mappings/yarnpatch.tiny) | B | 核对 1.21.1 本地映射补丁及官方补丁叠加冲突 |
| [mods.toml](src/main/resources/META-INF/mods.toml) | B | 原元数据迁出；保留必要 mod、mixins 声明 |
| `src/main/resources/META-INF/neoforge.mods.toml`（新增） | B | 目标版本元数据与正确版本范围 |
| [Setting.java](src/main/java/com/replaymod/core/Setting.java) | C | 旧键/默认兼容与新键定义 |
| [ReplayFoldersService.java](src/main/java/com/replaymod/core/files/ReplayFoldersService.java) | C | 路径解析、校验、生命周期及目录归属 |
| [ReplayFilesService.java](src/main/java/com/replaymod/core/files/ReplayFilesService.java) | C | 查询、恢复、缓存身份与安全发布 |
| [ManagedReplayFile.java](src/main/java/com/replaymod/core/files/ManagedReplayFile.java) | C | 工作文件生命周期与失败保源 |
| [RestoreReplayGui.java](src/main/java/com/replaymod/core/gui/RestoreReplayGui.java) | C | 现有恢复逻辑读取配置路径；不新增 GUI |
| [PacketListener.java](src/main/java/com/replaymod/recording/packet/PacketListener.java) | C | 活动录制工作目录固定 |
| [GuiSavingReplay.java](src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java) | C | 既有保存流程跨盘安全性与错误反馈 |
| [MarkerProcessor.java](src/main/java/com/replaymod/editor/gui/MarkerProcessor.java) | C | 编辑产物、备份、暂存路径一致 |
| [FullReplaySender.java](src/main/java/com/replaymod/replay/FullReplaySender.java) | C | 回放读取/缓存接入 |
| [MetadataInjector.java](src/main/java/com/replaymod/render/metadata/MetadataInjector.java) | C | 元数据操作使用受管临时路径 |
| [Lwjgl3Loader.java](src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java) | C | 原生库临时/缓存路径适配 |
| [ReplayModRender.java](src/main/java/com/replaymod/render/ReplayModRender.java) | C | 视频输出与渲染工作目录适配 |
| [Image.java](src/main/java/de/johni0702/minecraft/gui/versions/Image.java) | C | 图像相关临时路径接入 |
| 既有测试、构建产物与全部实现的审查 | D | 测试仅运行；不授权新增/改测试，不手改产物 |
| [PLAN.md](PLAN.md) | A | 本轮唯一写入文件；后续人员以只读交接为准 |

## B 工作与退出门槛
- 先确认环境与允许的 Gradle 联网解析下载，再最小调整白名单；保留现有 Shadow/remap/打包意图和功能。
- 可评估已核实发布的 `dev.architectury:yarn-mappings-patch-neoforge:1.21+build.6`；具体 layered 接法与本地补丁取舍待 B 依据实际解析诊断确认，禁止捏造 API。
- 旧 `libs/ReplayStudio-d9f7c11-slim.jar` 的 ProtocolVersion 最新为 `v1_20_3`；1.21.1 协议能力必须单独核实，不能用编译成功替代协议兼容。
- 不更换/编辑 vendored jar；已批准在 build.gradle 内调整迁移所必需的依赖坐标（含经官方来源核实的 ReplayStudio），但由此引出的范围外源码改动仍须另批。可选集成保持 compileOnly 与运行时存在性检查。
- B 完成最小构建文件调整并交接后，由父代理使用 mc_gradle 进行初始编译诊断；不提前启动 D，保持 A → B → C → D 的顺序，最终 D 仍必须执行。
- B 输出改动清单、候选依赖证据及待核实项；父代理补充首个真实编译失败与 C 所需精确文件清单。碰到未批文件即停报，不改测试、不删功能来制造通过。
- B 的退出不是宣称迁移成功：只有白名单改动可审阅、实际诊断可追溯、扩展范围交由父代理获批后，C 才能继续相关实现。

## C 存储设计与未授权扩展
- 保留 `recordingPath` / `cachePath` / `renderPath` 及原默认；`tempPath` / `recordingTempPath` / `rawPath` 是候选新增键，C 先核对真实定义，空值兼容旧行为。
- 六类用途须明确区分：temp 普通临时、cache 可再生缓存、recording 活动录制工作、record 成品、raw 备份、video 输出；录制工作 `.tmp` 绝非可随意清理的 temp。
- 相对路径统一基于游戏目录而非进程 cwd；保存、读取、查找、恢复、清理使用同一解析策略，不自动迁移旧数据。
- 配置加载前不得冻结错误默认；配置就绪后按会话/活动录制固定路径，避免运行中改设置导致读写分裂。
- 缓存身份基于规范化绝对录像路径的稳定编码/哈希；不对跨盘路径使用 relativize，明确规范化、大小写与链接处理策略。
- 拒绝危险根目录、用途交叉/包含导致的破坏性清理；仅删除有自身归属证明的条目，符号链接/junction 不得递归越界。
- 跨盘保存：在目标盘自有且独占创建的 `.part` 完成写入与验证，再以有明确 no-clobber 语义的方式发布；成功发布后才删源。
- `ATOMIC_MOVE` 遇已存在目标是否覆盖依实现，不能作为不覆盖保证；碰撞、离线、空间不足、权限失败须明确报错并保留源。
- 已核实 `ZipReplayFile(studio,input,output,cache)` 无独立 tmp 参数且 tmp 派生于 output；`close()` 无条件递归删除 tmpFiles。
- 必须先设计失败保留生命周期：禁止在 finally 直接 delegate.close() 导致可恢复 `.tmp` 被删；原库无安全能力则报告新增适配/依赖需求并停等批准。
- 不改全局 `java.io.tmpdir` 来替代组件路径配置；构建进程独立指定 tmp 与运行时配置不是同一件事。
- 所需新增 helper、协议迁移源码、部署配置或新增测试都列出精确路径/原因并再次请父代理申请，禁止自行扩展。

## D 验收矩阵与构建步骤
- 所有构建仅通过项目 mc_gradle；每次 args 包含 `-Djava.io.tmpdir=C:\Temp`，可加 `--no-daemon` / `--stacktrace`，不得 shell 调 wrapper。
- 诊断顺序：`tasks` → `compileJava` → `test` → `build`；必要时 `dependencies` 定位解析，`runClient` 仅在获准交互验证时使用。
- 初次联网解析建议 timeoutMs=1200000；后续编译/测试 600000，超时先保留日志并定位，不擅自换版本或清缓存。
- 记录 Java/Gradle 实际版本、任务、退出码、依赖解析及产物；核验目标元数据、映射、打包依赖及无旧版本误发布，不能跳过 D。
| 验收项目 | 状态与通过门槛 |
| --- | --- |
| 默认路径与旧配置兼容 | 未执行：默认、空值及旧数据发现行为保持兼容 |
| 中文/空格、绝对/相对路径 | 未执行：六类目录读写一致，相对路径以游戏目录解析 |
| 跨盘保存与目标碰撞 | 未执行：目标不覆盖，验证后发布，成功才删源；少量 C 或 D ↔ R 文件实测须另获批准 |
| 保存失败保源 | 未执行：离线/权限/空间不足/异常后工作文件仍可恢复，不被 close 清除 |
| 链接与清理边界 | 未执行：根目录、交叉用途、无归属条目、链接/junction 均不越界删除 |
| 既有原测试与 build | 未执行：运行现有测试和构建，完整报告失败；不得新增/改测试，拟加测试另批 |
| MC 客户端录制/回放/视频导出 | 未执行：真实 1.21.1 端到端验证及协议核实完成后才可宣称可用 |
| RAM disk 小文件验证 | 未执行：另批后在实际已挂载目录少量验证；R: 仅约 300MiB 可用，禁止容量压力测试 |

## 风险与来源
- 最高风险：旧 ReplayStudio 协议与破坏性 close 生命周期，叠加 Loom/Yarn/NeoForge 尚未验证的版本组合；源码迁移可能超出批准范围。
- 环境风险：R: 为 1GiB ImDisk、剩约 300MiB，卷离线/空间不足/重启会丢未持久化数据；RAM 盘断电无法恢复，不承诺与磁盘同等耐久性。
- 来源沿用父代理已核实记录，交接日期 2026-11-05；本轮未重新访问网页。版本发布存在不代表兼容通过，具体新版本 API 签名仍 UNVERIFIED。
- [NeoForge 21.1.249 POM](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249.pom)；[Yarn 1.21.1+build.3 POM](https://maven.fabricmc.net/net/fabricmc/yarn/1.21.1+build.3/yarn-1.21.1+build.3.pom)。
- [Architectury Loom 1.7.435 POM](https://maven.architectury.dev/dev/architectury/architectury-loom/1.7.435/architectury-loom-1.7.435.pom)；[Java 21 Files API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html)。
- 映射补丁发布及 ReplayStudio 构造/close 行为依据委派提供的此前 metadata/本地库核实；补丁具体文档链接与替代库能力待后续获准核实，不捏造签名或坐标。
- 当前授权不足以完成全部迁移；B 停报扩展清单 → 父代理获批 → C 实现 → D 构建与回归，未执行/未测项目必须如实保留标记。

## C 扩展审批清单（用户已通过本轮确认批准）

### 实际诊断与本次边界
- B 已完成获批构建调整；父代理另修正 NeoForge 仓库 `/releases`，加入 Architectury 仓库及官方 NeoForge 映射补丁 `1.21+build.6`，已核实下载归档的两处重命名内容。
- `mc_gradle tasks -Djava.io.tmpdir=C:\Temp --no-daemon --console=plain`：BUILD SUCCESSFUL，2m 2s；这不等于源码、协议或游戏运行通过。
- 完整 `compileJava` 诊断：205 errors、7 warnings、46 个去重报错源码路径，退出码 1。临时 `-Xmaxerrs 1000` 设置已移除，未改功能源码。
- 证据：[完整英文诊断日志](<C:/Users/zhenz/.gradle/daemon/8.8/daemon-4776.out.log#L441-L1440>)；其中第 1422–1423 行为 205 errors / 7 warnings。
- 46 个报错文件中，[PacketListener.java](<src/main/java/com/replaymod/recording/packet/PacketListener.java>)（14 条）及 [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>)（5 条）已在原 13 文件白名单，但本次同时申请将这两者的改动目的扩展为必要的 1.21.1 网络协议迁移，不借存储授权无限改写。
- 下表 44 个报错文件加 1 个明确联动的 TimerAccessor，共申请 45 个额外既有源码路径；与原 13 个存储路径并集为 58 个。仅限版本迁移及本任务所需改动，不是整个源码目录泛授权。

### 申请新增至白名单的 45 个既有源码路径
| 分组 | 精确文件路径 | 当前错误数 | 必要改动原因 |
| --- | --- | ---: | --- |
| 核心 | [core/KeyBindingRegistry.java](<src/main/java/com/replaymod/core/KeyBindingRegistry.java>) | 3 | 迁移事件订阅注解及 Identifier 构造。 |
| 核心 | [core/ReplayModBackend.java](<src/main/java/com/replaymod/core/ReplayModBackend.java>) | 2 | 迁移入口事件订阅，保留初始化流程。 |
| 核心 | [core/ReplayMod.java](<src/main/java/com/replaymod/core/ReplayMod.java>) | 4 | 适配 Identifier、资源包构造及覆写。 |
| 核心 | [core/versions/MCVer.java](<src/main/java/com/replaymod/core/versions/MCVer.java>) | 14 | 适配网络状态转换、矩阵栈及顶点提交。 |
| 核心 | [core/versions/Patterns.java](<src/main/java/com/replaymod/core/versions/Patterns.java>) | 8 | 适配缓冲、tick、矩阵、payload 标识及包编号。 |
| 核心 | [core/utils/WrappedTimer.java](<src/main/java/com/replaymod/core/utils/WrappedTimer.java>) | 10 | 迁移旧 RenderTickCounter 类继承与状态复制。 |
| 核心 | [core/versions/scheduler/SchedulerImpl.java](<src/main/java/com/replaymod/core/versions/scheduler/SchedulerImpl.java>) | 1 | 适配崩溃报告文本输出。 |
| 核心 | [core/utils/Utils.java](<src/main/java/com/replaymod/core/utils/Utils.java>) | 2 | 适配崩溃报告文本和落盘调用。 |
| 核心 | [core/utils/Restrictions.java](<src/main/java/com/replaymod/core/utils/Restrictions.java>) | 2 | 适配频道 Identifier 与自定义 payload 序列化。 |
| 核心 | [core/versions/LangResourcePack.java](<src/main/java/com/replaymod/core/versions/LangResourcePack.java>) | 2 | 适配资源包基类及语言资源 Identifier。 |
| 核心 | [core/gui/GuiReplayButton.java](<src/main/java/com/replaymod/core/gui/GuiReplayButton.java>) | 1 | 适配图标 Identifier 构造。 |
| 回放/时间轴 | [replay/camera/CameraEntity.java](<src/main/java/com/replaymod/replay/camera/CameraEntity.java>) | 2 | 适配 PlayerModelPart 类型引用。 |
| 回放/时间轴 | [replay/ReplayModReplay.java](<src/main/java/com/replaymod/replay/ReplayModReplay.java>) | 1 | 配合新计时器模型调整输入计时器安装。 |
| 回放/时间轴 | [replay/ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | 9 | 适配协议管线、登录处理器及矩阵栈。 |
| 回放/时间轴 | [replay/QuickReplaySender.java](<src/main/java/com/replaymod/replay/QuickReplaySender.java>) | 1 | 替换旧网络 handler 解码入口。 |
| 回放/时间轴 | [replay/NoGuiScreenshot.java](<src/main/java/com/replaymod/replay/NoGuiScreenshot.java>) | 2 | 适配 tick 与世界渲染，保留无 GUI 截图。 |
| 回放/时间轴 | [pathing/player/AbstractTimelinePlayer.java](<src/main/java/com/replaymod/pathing/player/AbstractTimelinePlayer.java>) | 5 | 配合计时器调整安装和进度读写。 |
| 回放/时间轴 | [pathing/player/ReplayTimer.java](<src/main/java/com/replaymod/pathing/player/ReplayTimer.java>) | 4 | 迁移计时器实例化、更新及状态复制。 |
| 录制 | [recording/ReplayModRecording.java](<src/main/java/com/replaymod/recording/ReplayModRecording.java>) | 6 | 迁移事件订阅及 payload 注册。 |
| 录制 | [recording/handler/RecordingEventHandler.java](<src/main/java/com/replaymod/recording/handler/RecordingEventHandler.java>) | 1 | 适配自身玩家生成包创建。 |
| 录制 | [recording/mixin/MixinNetHandlerPlayClient.java](<src/main/java/com/replaymod/recording/mixin/MixinNetHandlerPlayClient.java>) | 1 | 适配玩家列表包 registry-aware 序列化。 |
| 渲染/兼容 | [compat/shaders/ShaderBeginRender.java](<src/main/java/com/replaymod/compat/shaders/ShaderBeginRender.java>) | 1 | 适配渲染 tick 读取。 |
| 渲染/兼容 | [render/blend/BlendMaterials.java](<src/main/java/com/replaymod/render/blend/BlendMaterials.java>) | 2 | 替换失效缓冲分配引用，保留材质缓冲。 |
| 渲染/兼容 | [render/blend/Util.java](<src/main/java/com/replaymod/render/blend/Util.java>) | 3 | 适配缓冲分配及方块实体 NBT 的 registry 上下文。 |
| 渲染/兼容 | [render/mixin/Mixin_BlockOnChunkRebuilds.java](<src/main/java/com/replaymod/render/mixin/Mixin_BlockOnChunkRebuilds.java>) | 1 | 清理失效且有效分支未使用的类型 import，保留功能。 |
| 渲染/兼容 | [render/blend/BlendFrameCapturer.java](<src/main/java/com/replaymod/render/blend/BlendFrameCapturer.java>) | 1 | 适配 Blend 捕获 tick。 |
| 渲染/兼容 | [render/capturer/ODSFrameCapturer.java](<src/main/java/com/replaymod/render/capturer/ODSFrameCapturer.java>) | 2 | 迁移 ODS 着色器资源 Identifier。 |
| 渲染/兼容 | [render/rendering/VideoRenderer.java](<src/main/java/com/replaymod/render/rendering/VideoRenderer.java>) | 6 | 适配声音 Identifier、导出计时及矩阵栈。 |
| 渲染/兼容 | [render/gui/GuiVideoRenderer.java](<src/main/java/com/replaymod/render/gui/GuiVideoRenderer.java>) | 1 | 迁移预览占位图 Identifier。 |
| 渲染/兼容 | [render/hooks/EntityRendererHandler.java](<src/main/java/com/replaymod/render/hooks/EntityRendererHandler.java>) | 1 | 适配 GameRenderer 参数。 |
| 附加/预览 | [extras/playeroverview/PlayerOverviewGui.java](<src/main/java/com/replaymod/extras/playeroverview/PlayerOverviewGui.java>) | 2 | 适配 PlayerModelPart，保留帽层显示。 |
| 附加/预览 | [extras/advancedscreenshots/ScreenshotRenderer.java](<src/main/java/com/replaymod/extras/advancedscreenshots/ScreenshotRenderer.java>) | 1 | 适配截图 tick 读取。 |
| 附加/预览 | [simplepathing/gui/GuiKeyframeTimeline.java](<src/main/java/com/replaymod/simplepathing/gui/GuiKeyframeTimeline.java>) | 3 | 迁移时间轴线条缓冲和提交。 |
| 附加/预览 | [simplepathing/preview/PathPreviewRenderer.java](<src/main/java/com/replaymod/simplepathing/preview/PathPreviewRenderer.java>) | 46 | 迁移 Identifier、矩阵/缓冲/顶点及数值类型，保留预览。 |
| jGui | [versions/mixin/Mixin_RenderHudCallback.java](<src/main/java/de/johni0702/minecraft/gui/versions/mixin/Mixin_RenderHudCallback.java>) | 4 | 迁移失效的 NeoForge overlay 挂接目标，保留 HUD 回调。 |
| jGui | [versions/MCVer.java](<src/main/java/de/johni0702/minecraft/gui/versions/MCVer.java>) | 14 | 迁移 GUI 缓冲构建、顶点和提交。 |
| jGui | [MinecraftGuiRenderer.java](<src/main/java/de/johni0702/minecraft/gui/MinecraftGuiRenderer.java>) | 6 | 迁移纹理四边形绘制链。 |
| jGui | [element/AbstractGuiElement.java](<src/main/java/de/johni0702/minecraft/gui/element/AbstractGuiElement.java>) | 1 | 迁移 GUI 纹理 Identifier。 |
| jGui | [element/AbstractGuiButton.java](<src/main/java/de/johni0702/minecraft/gui/element/AbstractGuiButton.java>) | 4 | 迁移按钮声音与纹理 Identifier。 |
| jGui | [element/AbstractGuiSlider.java](<src/main/java/de/johni0702/minecraft/gui/element/AbstractGuiSlider.java>) | 2 | 迁移滑块及手柄纹理 Identifier。 |
| jGui | [container/AbstractGuiScreen.java](<src/main/java/de/johni0702/minecraft/gui/container/AbstractGuiScreen.java>) | 1 | 适配背景纹理绘制参数。 |
| jGui | [element/AbstractGuiTextField.java](<src/main/java/de/johni0702/minecraft/gui/element/AbstractGuiTextField.java>) | 1 | 适配字符合法性检查。 |
| jGui | [element/advanced/AbstractGuiTextArea.java](<src/main/java/de/johni0702/minecraft/gui/element/advanced/AbstractGuiTextArea.java>) | 1 | 适配多行文本字符合法性检查。 |
| jGui | [element/AbstractGuiCheckbox.java](<src/main/java/de/johni0702/minecraft/gui/element/AbstractGuiCheckbox.java>) | 1 | 迁移复选框声音 Identifier。 |
| 计时器联动 | [core/mixin/TimerAccessor.java](<src/main/java/com/replaymod/core/mixin/TimerAccessor.java>) | 非上述 205 条 | 当前将旧字段 Accessor 挂到 RenderTickCounter；该类已变接口，必须与计时器同步核对和迁移目标。 |

### 本次不包含与后续门槛
- 不申请新增 helper：已证实必需数量为 0；不预先创建猜测的框架或适配层。
- 不新增或修改测试，不改部署 JSON、.env、vendored jar 或 Mixin 配置资源。若实际诊断证明还需其他文件，先列出精确路径及原因再申请。
- 原 13 文件白名单中本轮无 error 的其余 11 个，只能按原先的存储目标修改，不能顺手优化弃用警告。
- 已读取的 Mixin 配置中相关 Java 类均已登记；资源列表暂无明确修改必要。运行时注入目标仍须另验，不能把编译错误清单当作运行兼容保证。
- 后续逐文件对照原项目上游 [1.21 分支版本声明](https://raw.githubusercontent.com/ferriarnus/ReForgedPlay/1.21/gradle.properties) 与 [构建依赖](https://raw.githubusercontent.com/ferriarnus/ReForgedPlay/1.21/build.gradle)；父代理已实际读取其 MC 1.21.1 声明。这不是整库替换授权，也不照搬新增可选集成或浮动 master-SNAPSHOT 依赖。
- 在已批准构建文件范围内继续核验 ReplayStudio 的 1.21.1 协议能力和安全关闭生命周期、Iris 的目标版本依赖；不猜坐标、不改 vendored jar、不删除集成功能换取通过。
- 父代理已核实原项目上游 1.21 分支当前固定提交为 `a8dfcdce1947ac2fb73375d476710a6500b1ada5`（2025-08-30，提交说明 experimental syncing）。逐文件借鉴时可固定该提交，但仍须过滤无关实验改动，并核对本地目标 API，不整文件盲替换。
- ReplayStudio 候选固定版本：`com.github.ReplayMod:ReplayStudio:1e96fda605`。依据为官方 ReplayMod 历史提交 `5b766b4fe76b38e7b8b79e35d7736abef09d3e3b` 的 [ReplayStudio 子模块指针](https://api.github.com/repos/ReplayMod/ReplayMod/contents/libs/ReplayStudio?ref=5b766b4fe76b38e7b8b79e35d7736abef09d3e3b)，指向完整 SHA `1e96fda6052562477a54a7ced778f25a547f40ae`；JitPack 已返回短 SHA 版本状态 ok，且其 [POM](https://jitpack.io/com/github/ReplayMod/ReplayStudio/1e96fda605/ReplayStudio-1e96fda605.pom) 实读 HTTP 200。现已在 build.gradle 采用 normal publication，并实际解析成功（daemon-21208.out.log，dependencies runtimeClasspath 57s BUILD SUCCESSFUL，非编译）。实物 SHA1 d4e4832787a7f6379f21af3daebb5591d32be0c9 / SHA256 6a3530756b92f54ac831b9da4bab1c544e0b651d75effdf99a2606ddb9175955；ProtocolVersion.v1_21=767 与原版 1.21.1 version.json 的 protocol_version=767 匹配，具备 1.21 转换类及映射资源。只证明静态协议支持，不等于回放运行验证。normal 已嵌 ViaVersion/重定位 Guava/PacketLib/重定位 Netty/dnsjava；commons-cli 和 commons-collections4 仍需 bundle 传递依赖，旧 OpenNBT 的 include 与 Via 内嵌 NBT 不同包不能误删。最终产物打包由 D 核验；不要另加重复 ViaVersion/Guava/Netty。
- Iris 候选 `maven.modrinth:iris:1.8.6+1.21.1-neoforge` 的 [POM](https://api.modrinth.com/maven/maven/modrinth/iris/1.8.6+1.21.1-neoforge/iris-1.8.6+1.21.1-neoforge.pom) 已实读 HTTP 200，并与原上游 1.21 构建声明一致；现已采用 modCompileOnly 且 Loom 下载/重映射成功，4 个集成文件已获下节精确授权。实物新 root 为 net.irisshaders.iris，HandRenderer 在 pathways 包（不是 pipeline）。generalCommonUniforms(UniformHolder, FrameUpdateNotifier, PackDirectives)V；uniform1b/1i 使用 BooleanSupplier/IntSupplier；loadExternalShaderpack(String)Z 内 getShaderpacksDirectory()Path 仍存在两处。IrisConfig 旧方法仍在，reload/save 仍 throws IOException。Iris 不打包、不作为运行依赖；最终运行时注入另验。
- 批准本清单后，C 可分片顺序实施，父代理逐片核对真实 diff 并经 mc_gradle 诊断；最终 D 仍须执行测试、build 与复核。游戏/真实 RAM disk 功能未测必须如实标记。

## C 补充精确授权（本轮 ask_user_question 已批准 5 个既有源码文件）
- 用户选择“批准这 5 个文件”，目的仅为 1.21.1 计时器字段类型及 Iris 1.8.6 兼容引用；源码白名单并集现在为 63 个既有文件，不授权其他源码、测试、资源或配置。
- `src/main/java/com/replaymod/core/mixin/MinecraftAccessor.java`：getTimer/setTimer 精确使用 RenderTickCounter.Dynamic，与本地目标 JAR 的 private final 字段描述符一致；保留 @Mutable。
- `src/main/java/com/replaymod/render/capturer/IrisODSFrameCapturer.java`：迁移 Iris/IrisConfig 引用。
- `src/main/java/com/replaymod/render/mixin/Mixin_AddIrisOdsShaderUniforms.java`：迁移 uniform 类引用并核验目标方法。
- `src/main/java/com/replaymod/render/mixin/Mixin_LoadIrisOdsShaderPack.java`：迁移 Iris 引用及 Redirect 描述符并核验目标方法。
- `src/main/java/com/replaymod/replay/mixin/Mixin_ShowSpectatedHand_Iris.java`：核验并修复旧 HandRenderer Mixin 目标。
- C1 两次子代理已结束；父代理另完成 MinecraftAccessor 的 Dynamic get/set 签名。C1 复核后全量诊断为 149 errors / 11 warnings，日志 C:/Users/zhenz/.gradle/daemon/8.8/daemon-21164.out.log（临时 -Xmaxerrs 1000 已移除）。C1 源码未报错；整项目仍失败，运行时计时器/Mixin 未测。
- C2 约定：MCVer.asMc 返回 NetworkPhase；PLAY codec 需要真实 registry manager/connection type；Restrictions.Payload 的 ID/CODEC 需用 optional().playToClient 注册，不能静默丢弃限制包。计时器为 Dynamic，原始进度通过 TimerAccessor，双参数 beginRenderTick 保留单参数输入钩子。

## C2 网络迁移交接（C1 已完成，C3/C4 未启动）
- 本片只修改以下 7 个既有源码：`src/main/java/com/replaymod/recording/ReplayModRecording.java`、`src/main/java/com/replaymod/recording/handler/RecordingEventHandler.java`、`src/main/java/com/replaymod/recording/mixin/MixinNetHandlerPlayClient.java`、`src/main/java/com/replaymod/recording/packet/PacketListener.java`、`src/main/java/com/replaymod/replay/ReplayHandler.java`、`src/main/java/com/replaymod/replay/FullReplaySender.java`、`src/main/java/com/replaymod/replay/QuickReplaySender.java`。如有必要可最小修正已授权的 `core/versions/MCVer.java` 或 `core/utils/Restrictions.java`（均在 src/main/java/com/replaymod 下），必须报告原因；不得改其他源码、构建、资源、测试或本 PLAN。
- 只做必要的 1.21.1 版本迁移；ReplayHandler 的同文件矩阵栈类型错误可一起适配，其余渲染交给 C3，存储交给 C4。不要删除功能、跳过限制包、使用空 registry 或静默失败来骗编译。
- 工作目录 C:/Users/zhenz/ReForgedPlay；必须先加载 minecraft-java-build/minecraft-neoforge-mod 技能及参考，再依据本地目标 JAR javap/官方源码核实 API。目标 JAR：`.gradle/loom-cache/minecraftMaven/net/minecraft/neoforge-21.1.249-minecraft-merged-7d32a903de/1.21.1-loom.mappings.1_21_1.layered+hash.933728205-v2-neoforge-21.1.249/neoforge-21.1.249-minecraft-merged-7d32a903de-1.21.1-loom.mappings.1_21_1.layered+hash.933728205-v2-neoforge-21.1.249.jar`；同目录有 sources.jar。
- build.gradle 已选择并实际成功解析 ReplayStudio `1e96fda605` normal publication 和 Iris `1.8.6+1.21.1-neoforge`，旧 vendored JAR 未改；dependency-audit/task-1 已完成。实物 ReplayStudio 位于 C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/com.github.ReplayMod/ReplayStudio/1e96fda605/d4e4832787a7f6379f21af3daebb5591d32be0c9/ReplayStudio-1e96fda605.jar。v1_21=767 与 MC1.21.1 协议相同；ReplayFile 的 relocated Guava Optional、RandomAccessReplay.load(Consumer<Double>)、重定位 ByteBuf 与旧 slim ABI 一致，QuickReplaySender.initialize 是项目自己的 wrapper，不能仅因名字/重定位盲改。完整打包/生命周期证据见本计划依赖审查与 C4 节。
- C2 崩溃恢复：前内容代理 5c6038fd-7556-454e-a457-0e41595c36b4 已被 Harness 停止，无完成报告；上述 7 文件已有部分修改落盘但未做交接编译。用户明确要求继续并重启受影响子代理。新 C2 必须先读当前 git diff 与完整调用链，接续收尾而非覆盖/回退已存修改；完成自审后给出真实改动清单和未验证项，再交父代理编译。不要把“已有修改”当完成，不启动 C3/C4。
- C2 不运行 Gradle、不创建临时脚本或新测试；父代理在本片交接后诊断编译。Git 只读 status/diff/log；禁止安装、RAM 盘操作或游戏运行。报告真实 diff 文件清单、已核实 API、尚未验证的协议切换/限制处理及范围外阻塞。
- 必须完整迁移 LOGIN→CONFIGURATION→PLAY、快进/重启回放、录制流的状态切换和原始包字节；读写必须保持一致。Restriction payload 由 ID/CODEC 注册，未知/损坏限制应终止回放并报错，不能被 FullReplaySender 的 catch-and-skip 吞掉；释放 buffer 并保持 channel 线程规则。

## C4 只读存储审查交接（设计已审查，尚未实施或实测）
- storage-audit/task-2 已完成；此节为后续 C4 约束，不提前启动 C4。源码落点仍是原 13 个既有文件，不需要新 helper 文件。不得改 DelegatingReplayFile、ConnectionEventHandler、render/Setting、GuiEditReplay 或部署 JSON。
- folders 构造发生于 settings load 前，只保存 registry；第一次业务 getter 一次性解析并冻结 6 个 roots，不分别冻结。旧 recordingPath 是成品根，旧 cachePath/renderPath 保留键与默认值，render 读取已有 render.Setting.RENDER_PATH。相对路径统一按 gameDir；新 work/raw 默认是 finished/recording、finished/raw，不能用一律禁止嵌套破坏默认；后续路径改动重启生效，无 GUI/全局 java.io.tmpdir 改动。
- 实际链为 ConnectionEventHandler 以 workRoot/timestamp.mcpr→files.open(path)→PacketListener→MarkerProcessor→GuiSavingReplay。必须保留调用方 logicalPath 与 save 后该路径存在的契约，同时底层 ZipReplayFile output 放入 UUID-owned workspace 的 generation 中；其 output+'.tmp' 才能独立落工作根。新录制同名碰撞不能自动作为已有录像编辑打开。
- resolved normal JAR 的实物字节码确认：save() 全局 temp→saveTo→virtual close 清 tmp→删旧 output→move→reopen；saveTo 关闭写流并直写/截断 target，但不清 changed/removed、不重建读取基线；close 不保存、递归删 tmp。字段全 private，无非破坏性 close。C4 禁止直接 delegate.save()，saveTo-only 也不能冒充 save()：RestoreReplayGui:117–127 首次 save 提交 changed 后才能同名重写 packet，否则输入被自截断。
- 可在 ManagedReplayFile 既有文件内实现 mutable delegate/嵌套 forwarding adapter（基类 delegate 是 private final，不能误以为可直接换）。save 形成完整快照、验证/发布后，以提交 ZIP 为新 input、新 generation 为 output 重建 delegate。区分 OPEN_DIRTY/COMPLETE_SNAPSHOT/PUBLISHED/NEEDS_RECOVERY/CLOSED 及已发布但源待清理；不向正在读的原 ZIP 直接 saveTo。
- close 集中保源，不仅依赖 PacketListener；GuiEditReplay 和 MarkerProcessor 有 try-with-resources。失败时没有完整独立快照绝不调用破坏性 delegate.close。可先在 owned work 生成/验证恢复 ZIP，再 close；若 work 也不可写或写流关闭失败，保留原树与锁并抛错，不假称全部句柄释放。此极端路径可能到重启才释放句柄；若必须保证所有失败仍释放 private 资源，库 API 不足，需要另批适配。
- 发布必须统一覆盖 Files 初扫/MarkerProcessor raw/GuiSavingReplay 成品/Restore 拒绝恢复等裸 move：源保留→目标卷自有事务区独占 part→关闭流、force 与完整性校验→不覆盖发布→成功才清源。CREATE_NEW 与 SAVE_EXISTING 分开；已有录像编辑需验证已打开输入身份、保留旧版及完整新快照后明确更新，不拒绝所有已有文件，也不覆盖陌生目标；source==target no-op。不能把 exists+ATOMIC_MOVE 当 no-clobber。
- 发布 provider 证据：父代理实读 https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileCopy.java ，Windows 标准 provider 普通 move（无选项）同卷最终 MoveFileEx(...,0)，不覆盖目标；ATOMIC_MOVE 反而传 MOVEFILE_REPLACE_EXISTING。目标卷 part→final 可采用已核实的 Windows 无选项 move，从而不要求 ImDisk 一定支持 hard link。其它 provider 可用 Files.createLink(final,part) 原子不覆盖发布；不支持时保留源/part并报错，不能盲退成会覆盖的 rename。必须明确 provider/同卷约束；不宣称跨卷原子性或断电耐久性。
- 恢复原地发现：owned work 事务、当前 finished 的 legacy .mcpr.tmp、默认旧 gameDir/replay_recordings 及旧 recording 子目录去重扫描；不先跨卷搬非空 tmp。GUI 区分实际 workspace 和 logical target。历史任意自定义根无记录无法凭空找到。校验 source 元数据与路径；legacy 名字不是删除授权，先验证/安全复制再交给库。拒绝恢复转 owned UUID 隔离状态，不删除同名 .del。PacketListener 现先写 .no_recover 后 raw.saveTo，失败后启动会删唯一 tmp，必须改为持久化验证成功后才标记或采用有事务身份的新记录；裸 marker 永不授权删除。
- 缓存键改版本前缀+规范绝对录像 identity 稳定 hash，避免跨盘 relativize 与 URL decode 路径注入；Windows identity 大小写策略明确，不在 Unix 无差别 lowercase。hash 不可逆，清理读已验证 owned metadata；旧无归属 cache 保留/只读兼容，不将目录名解释为任意路径。根及祖先/子项 NOFOLLOW 校验，Windows junction 的 isOther reparse 一并拒绝；库内部删树不防链接，所以扫描、构造前同样验。清理只针对 schema/id/边界均匹配的 owned 树；后缀、年龄、配置根本身都不是所有权。路径预检不等于抗恶意并发替换的绝对安全。
- MetadataInjector 现从 temp 拷回时截断原视频且 finally 删除唯一 backup，必须完整候选→验证→备份保护更新，失败保原/backup。Lwjgl3Loader/Image 用独占 temp 子区；natives 仍落 cache。FullReplaySender 的资源包临时目录移到构造内的 frozen temp；replay hash须校验预期 hex 单段，两端 stream 关闭，清理 owned 树。ReplayModRender getter 使用 frozen video；现有 GUI 自由选择输出仍保留，不能宣称每个输出都被强制放配置根。
- 现有测试只有 src/test/java/com/replaymod/simplepathing/SPTimelineTest.java，共 41 个 @Test，属于路径时间轴测试，不覆盖存储。D 必须报告既有测试与静态审查证据边界；没有授权新增/改测试、启动游戏或 RAM disk 实验。

## C3 运行时注入追加授权（用户已批准 11 Java + 1 资源文件）
- 本轮 ask_user_question 用户选择“批准11个Mixin及1个资源文件”。Java 精确白名单并集由 63 扩至 74；另只允许 src/main/resources/pack.mcmeta 的 pack_format 从 4 改为 34（原版 1.21.1 minecraft-client.jar/version.json 实物 resource=34，data=48）。不授权其它资源、源码、测试或部署配置。Mixin 配置 JSON 不在追加授权内。
- 以下 11 个已有源码由 mixin-audit/task-3 用本地 named JAR javap -p -s/-c 核验，不是仅凭编译器；修复在 C2 完成后顺序 C3 实施，保持真实矩阵/roll/全景/立体含义，禁止空 MatrixStack 或删回调骗通过：
  1. src/main/java/com/replaymod/core/mixin/MixinMinecraft.java：两处 GameRenderer.render(FJZ)V 更新为 render(RenderTickCounter,boolean)V。
  2. src/main/java/com/replaymod/core/mixin/MixinGameRenderer.java：renderWorld callback 更新为 (RenderTickCounter,CI)，renderHand 为 (Camera,float,Matrix4f,CI)，renderHand:Z 字段 anchor 仍在。
  3. src/main/java/com/replaymod/render/mixin/Mixin_ChromaKeyColorSky.java：renderSky(Matrix4f,Matrix4f,float,Camera,boolean,Runnable)V，旧首参 MatrixStack 不再正确。
  4. src/main/java/com/replaymod/render/mixin/Mixin_ForceChunkLoading.java：WorldRenderer.render(RenderTickCounter,boolean,Camera,GameRenderer,LightmapTextureManager,Matrix4f,Matrix4f)V callback；setupTerrain(Camera,Frustum,ZZ)V anchor 仍在。
  5. src/main/java/com/replaymod/render/mixin/Mixin_Omnidirectional_Rotation.java：renderWorld HEAD callback 参数更新；变换语义不能丢。
  6. src/main/java/com/replaymod/render/mixin/Mixin_Stereoscopic_Camera.java：renderWorld callback 参数更新；getBasicProjectionMatrix RETURN 的仅 CIR callback 不受参数变化影响，不盲改。
  7. src/main/java/com/replaymod/replay/mixin/MixinCamera.java：renderWorld callback 及 anchor 更新；旧 Camera.getPitch()F 在本方法已不存在，实际 Camera.getRotation()Quaternionf 在 offset485。
  8. src/main/java/de/johni0702/minecraft/gui/versions/mixin/MixinGameRenderer.java：render callback 更新为 (RenderTickCounter,boolean,CI)；ClientHooks.drawScreen(Screen,DrawContext,IIF)V anchor 仍在。
  9. src/main/java/de/johni0702/minecraft/gui/versions/mixin/MixinMouseListener.java：Mouse.method_1602 不存在，实际拖动 lambda private method_55795(Screen,DDDD)V，内部 Screen.mouseDragged(DDIDD)Z。method_1611/method_1605 和 scroll descriptor 有效。
  10. src/main/java/com/replaymod/render/mixin/MixinParticleManager.java：Particle.buildGeometry(VertexConsumer,Camera,F)V 已从 renderParticles 移入 render(LightmapTextureManager,Camera,F,Frustum,Predicate)V 内 offset203；必须修 method selector，不仅 target 字符串。
  11. src/main/java/com/replaymod/render/mixin/Mixin_Omnidirectional_Camera.java：getBasicProjectionMatrix 内实际 Matrix4f.perspective(FFFF)Matrix4f，不是 setPerspective；两处 ModifyArg 均需更新。
- 已排除：MinecraftAccessor Dynamic、ChunkRenderingDataPreparer 3 字段、Window framebufferWidth/Height及invoker、ClientWorld.entityList、PlayerEntity.selectedItem、HeldItemRenderer字段、ClickableWidget.height、Screen.addDrawableChild 均匹配，不扩范围。Keyboard 的 @Group lambda 替代分支存在；CallbackInfo-only HUD/shader callback 不必复制目标全部参数。上述是最小已证实故障集合，不是全运行时兼容证明。

## C3 顺序内容片划分（均须等 C2 正式交接后启动）
- C3a：渲染/相机/预览/Iris及非jGui的9个新增Mixin，共25个已授权源码；精确范围为以下相对 `src/main/java/com/replaymod/` 的路径。只能做1.21.1必要迁移，不触碰存储逻辑，不改C2文件、PLAN、构建、资源或测试。新tick读值须区别 pause/freeze 与 raw tickDelta；矩阵不可新建空栈替代真实变换。Iris是compileOnly，兼容Mixin保留可选存在性与实际新包名。C3a由内容代理完成源码后立即交接父代理诊断，不自己跑Gradle。
  - `replay/camera/CameraEntity.java`、`replay/NoGuiScreenshot.java`、`compat/shaders/ShaderBeginRender.java`。
  - `render/blend/BlendMaterials.java`、`render/blend/Util.java`、`render/blend/BlendFrameCapturer.java`、`render/mixin/Mixin_BlockOnChunkRebuilds.java`、`render/capturer/ODSFrameCapturer.java`、`render/rendering/VideoRenderer.java`、`render/hooks/EntityRendererHandler.java`。
  - `extras/advancedscreenshots/ScreenshotRenderer.java`、`simplepathing/preview/PathPreviewRenderer.java`。
  - `render/capturer/IrisODSFrameCapturer.java`、`render/mixin/Mixin_AddIrisOdsShaderUniforms.java`、`render/mixin/Mixin_LoadIrisOdsShaderPack.java`、`replay/mixin/Mixin_ShowSpectatedHand_Iris.java`。
  - `core/mixin/MixinMinecraft.java`、`core/mixin/MixinGameRenderer.java`、`render/mixin/Mixin_ChromaKeyColorSky.java`、`render/mixin/Mixin_ForceChunkLoading.java`、`render/mixin/Mixin_Omnidirectional_Rotation.java`、`render/mixin/Mixin_Stereoscopic_Camera.java`、`replay/mixin/MixinCamera.java`、`render/mixin/MixinParticleManager.java`、`render/mixin/Mixin_Omnidirectional_Camera.java`。
- C3b：C3a完成后再启动；15个源码及1个资源文件。Java范围为原表3个GUI调用方 `src/main/java/com/replaymod/render/gui/GuiVideoRenderer.java`、`src/main/java/com/replaymod/extras/playeroverview/PlayerOverviewGui.java`、`src/main/java/com/replaymod/simplepathing/gui/GuiKeyframeTimeline.java`；原表第138–147行的10个jGui文件；本次追加的 `src/main/java/de/johni0702/minecraft/gui/versions/mixin/MixinGameRenderer.java` 和 `src/main/java/de/johni0702/minecraft/gui/versions/mixin/MixinMouseListener.java`。另只改 `src/main/resources/pack.mcmeta` 的 pack_format=34。其它源码/资源/构建/测试仍不得修改；完成交接后由父代理编译。
- task-4 为并行只读矩阵核查，不能修改文件；结果由父代理合并后供C3a引用。C3a/C3b源代码写入始终串行。
- C4后续可顺序分为基础4文件（Setting/ReplayFoldersService/ReplayFilesService/ManagedReplayFile）与其余9个调用方；拆片不改变原13文件授权和存储设计。任何新公共API须先在本计划交接，不能让后片猜接口。D最终独立构建/既有测试不可省略。

## C2恢复交接、诊断与C2.1追加批准
- 恢复子代理 b5335203-496d-4650-a376-4e33ce1416cf 已正式完成C2七文件内容交接，未构建/测试/游戏。未改MCVer、Restrictions、PLAN、构建、资源、脚本；新增recordingFailure及restriction/split原始预检需在C4保留。
- C2实现了真实connection codec、decoder重定位和raw复制；bundle资源包；客户端线程RAR dispatch；LOGIN→CONFIGURATION→PLAY；退出快速模式重新LOGIN；同步EOF/零时间重启/引用释放；optional payload注册与NeoForge分片restriction校验。不支持的非空限制维持fail-closed，不能宣称支持任意模组registry重建或运行验证。
- 父代理compileJava（Java21/Gradle8.8，-Djava.io.tmpdir=C:\Temp，--info）31s失败：128 errors / 13 warnings，只输出前100条。日志 `C:/Users/zhenz/.gradle/daemon/8.8/daemon-21764.out.log`。已显示的C2错误为FullReplaySender.java:652的DisconnectS2CPacket.getReason()，须按实物record accessor修正；其余已显示错误主要在待做C3，不声称隐藏28条没有C2错误。上一次C1后的全量为149错误。
- C2确认首个未知LOGIN query在现有onQueryRequest HEAD录制启动前已被vanilla skipBytes，Unknown payload.write为空；当前明确报recordingFailure保源，不伪造缺失数据。现有MixinNetHandlerLoginClient启动签名保持，不获准修改它。
- 用户在 ask_user_question `login-raw-capture-scope` 选择“批准该文件”：**追加既有** `src/main/java/com/replaymod/recording/mixin/MixinClientConnection.java`，仅为解码前首个LOGIN查询原始字节捕获、必要生命周期/管线重定位和清理；此Mixin已有注册，不新增资源/依赖。
- **下一片C2.1**：仅写新批准的MixinClientConnection及必要的原C2七文件；优先最小范围PacketListener、FullReplaySender和MixinClientConnection，若需其它已批准C2文件须报告理由。先读当前diff，不覆写整片。实现首包保真并修复上项编译访问器；packet引用/ByteBuf所有权、登录失败/禁用录制/连接关闭的有界保留与释放、handler可能异步启动的排序与重复记录必须覆盖，不能只保留“最后一个buffer”而丢首包。核验实际ClientConnection与LOGIN codec/Netty API，不伪造未知payload、不吞录制错误。不得写C3/C4逻辑、PLAN、测试、脚本、资源或构建；不跑Gradle/游戏。完成正式交接后由父代理再编译，C3a必须等待。

## C3a矩阵实物与Iris时钟新增批准（task-4只读结论）
- GameRenderer.renderWorld(RenderTickCounter)V：projection=slot9，slot10 MatrixStack只放hurt/bob，已在@289乘入projection；nausea也改projection。真正view=slot14，在@485取camera.getRotation、@495 conjugate(new Quaternionf())、@509 Matrix4f.rotation(Quaternionfc)、@512 STORE。它同时传setupFrustum(@557)、WorldRenderer.render(@580，view index5/projection index6)、renderHand(@675)。不能用bobStack代view，不能只改world实参漏掉frustum。
- core MixinGameRenderer的FIELD renderHand:Z anchor仍在@654，处于world+NeoForge AFTER_LEVEL之后、hand清depth之前。保留PostRenderWorldCallback接口，捕获真view后new MatrixStack().multiplyPositionMatrix(view)（实际方法同步维护normal）。现有MixinExtras0.3.5支持 `@Local(ordinal=1) Matrix4f viewMatrix`，0是projection；无需新依赖。CI+counter+Local可用。不可只set position遗漏normal。WorldRenderer已在返回前pop/apply全局modelView，预览再乘view一次是正确的。
- PathPreviewRenderer模型栈改Matrix4fStack.mul与pushMatrix/popMatrix，并保留applyModelViewMatrix；不乘projection。
- roll：replay MixinCamera对 `Matrix4f.rotation(Quaternionfc)` 的参数index0用@ModifyArg（JOML target remap=false），CameraEntity时返回 `new Quaternionf().rotationZ(radians(roll)).mul(original)`，否则原值；不原地污染Camera quaternion，保持 Rroll*Rcamera^-1。
- cube/stereo在两个原Mixin分别@ModifyVariable(renderWorld,@At("STORE"),index=14,require=1)，实物唯一Matrix4f astore14=@512。cube新矩阵rotation(face).mul(view)，保留FRONT0Y/RIGHT+90Y/BACK180Y/LEFT−90Y/TOP−90X/BOTTOM+90X与scheduleTerrainUpdate。stereo translation(LEFT+0.1/RIGHT−0.1,0,0).mul(view)。CaptureData两枚举互斥，不靠Mixin优先级决定roll顺序。立体projection RETURN原有LEFT+0.07/RIGHT−0.07左乘继续保留；全景projection真实目标perspective(FFFF)，不是setPerspective。
- EntityRendererHandler创建该次capture的RenderTickCounter匿名适配器，getTickDelta(boolean)固定partialTicks，另getLastFrameDuration/getLastDuration取当时mc counter快照；调用render(counter,true)。Constant(float)构造不是public，不能new；不能裸传全局counter丢掉partialTicks，也不能beginRenderTick推进时间轴，ONE/ZERO不是替代物。
- **不能丢旧finishTimeNano语义**：Iris1.8.6 MixinGameRenderer.iris$startFrame中直接Util.getMeasuringTimeNano()→SystemTimeUniforms.TIMER.beginFrame(long)，不读counter；getLastDuration不是绝对纳秒值。Timer实际有beginFrame(long)/reset/getFrameTimeCounter/getLastFrameTime。
- 用户在 ask_user_question `iris-frame-clock-scope` 选择“批准两文件”：C3a **新增** `src/main/java/com/replaymod/render/mixin/Mixin_IrisFrameTime.java`，并仅在 **既有** `src/main/resources/mixins.render.replaymod.json` 增加注册。用途仅保留捕获时既有FRAME_TIME_FROM_WORLD_TIME/固定输出帧时间；未装Iris时不启用，不新增依赖或修改其它配置。EntityRendererHandler（已授权）可暴露当前捕获时间与受try/finally保护的捕获状态。实际Mixin目标/帧时间重置边界须查实物，避免影响普通游玩或重复cube面造成动画推进。此授权覆盖上文C3a不准资源写的一般限制，仅限这一注册。
- MixinParticleManager现有finally rotation.set(org.w,org.x,org.y,org.z)参数顺序错误，本地JOML是x,y,z,w；同一已批准文件可改rotation.set(org)。全部task-4为静态核查，未编译/应用Mixin/验证画面。

## C4a→C4b最小接口草案（task-5；尚未实现）
- C4a四文件仅Setting、ReplayFoldersService、ReplayFilesService、ManagedReplayFile。C4b原13中其它9个。无需新源码；基类DelegatingReplayFile不在范围。保持既有public构造、getter、open、throws以及ReplayFile重定位类型；不建立不必要通用框架。
- Setting新增TEMP_PATH=advanced.tempPath、RECORDING_TEMP_PATH=advanced.recordingTempPath、RAW_PATH=advanced.rawPath，默认均空串。空temp回退启动时java.io.tmpdir（只读不改），空work/raw为冻结finished下recording/raw。旧recordingPath/cachePath/renderPath键/默认不变。
- Folders只新增 `Path getTempFolder() throws IOException`、`Path getVideoFolder() throws IOException`。首次业务getter一起冻结六root，解析与创建分开；不为拿record路径而写探测所有其它盘。具体操作再校验/创建所需目录。相对路径统一gameDir。缓存稳定hash+owned metadata，getReplayPathForCache未知条目抛IOException，cleanup逐项跳过，不猜URL、不返回null。移除dos:hidden写入，不能给自定义共享cache/temp根施加属性副作用。外部不得据getTempFolder递归清根。
- Files保留旧接口，草案新增8入口：`Path createTempFile(String prefix,String suffix)`；`Path createTempDirectory(String prefix)`；`void deleteOwnedTemp(Path path)`；`Path publishReplay(Path source,Path target)`；`void discardReplay(Path path)`；`ReplayFile openRecovery(Path recoveryKey)`；`void discardRecovery(Path recoveryKey)`；`Replacement prepareReplacement(Path existingTarget)`，均throws IOException。仅嵌套 `public static final class Replacement implements AutoCloseable`，公开 `Path stagedPath()`、`void commit() throws IOException`、`void close() throws IOException`。
- temp接口独占创建和登记owned条目，delete拒未知/链接，不删共享根。publishReplay仅CREATE_NEW已成型录像，返回确切target，不暗改名、不覆盖；source同一identity no-op。保留source直到目标卷owned part关闭force验证且no-clobber发布，先检查source身份和活动锁。发布后source清理失败登记cleanup-pending并警告，正常返回target，不误报未发布。discardReplay只接受服务登记生成且无活动锁的录像，不是任意删除API。
- Replacement只给MetadataInjector和内部SAVE_EXISTING：锁并捕获原件identity，目标卷owned stagedPath，调用者关流且校验格式后commit；复核身份、保留旧版、新内容发布。失败或未commit close保留恢复材料，不删除唯一候选/旧版。发布后清理失败不是提交失败。Windows用已核验同卷普通move；其它provider只有经验证no-clobber能力才使用，否则保源报错，不能把ATOMIC_MOVE当不覆盖保证。
- open(path)：先识别已登记recovery key；work根内普通路径表示新录制CREATE_NEW（存在拒绝），work外按存在性选新建/编辑。open(null,output)总是CREATE_NEW，已存在拒绝。open(input,output)的非null input必须存在且安全；同一identity明确SAVE_EXISTING（含work已保存录像），否则只读input基线+CREATE_NEW output。MarkerProcessor读取已保存work必须改open(path,path)，拆分仍open(null,output)，不新增mode公共框架。
- Managed mutable forwarding adapter只嵌在本类，实现完整ReplayFile转发；不得改基类private final delegate。服务可用package-private factory/Session，public兼容构造保留，但不能宣称无事务上下文的任意外部delegate路径拥有新保障。logicalInput/output固定，底层Zip.output为UUID-owned work/generation，cache用logical身份。
- save：停写关流→目标卷owned part完整ZIP→验证/登记snapshot→CREATE_NEW或身份验证SAVE_EXISTING提交→新delegate(input=已发布ZIP,output=新generation)。正常返回logicalOutput已存在；不可原Zip.save，不可仅saveTo而不rebase。首次CREATE_NEW成功后本handle后续save更新自己那一identity。若已发布但rebase失败，保留回执，异常明确已发布，后续仅重试rebase不重复发布。
- saveTo是新目标导出（碰撞拒绝），不改变logicalOutput或基线，但登记当前revision完整snapshot供close保源；目标即logicalOutput则走save，指向其它打开input/陌生既有文件拒绝。任何后续内容write/remove让旧snapshot不再覆盖当前revision；全部高阶写方法和返回流均要跟踪，cache写不算录像dirty。保存期间禁并发写，旧generation流不能跨rebase继续使用。
- close幂等：无未保全内容或当前revision独立完整snapshot才可破坏性库close；否则先owned work生成/验证恢复ZIP。也失败则保留tmp/delegate/锁并抛IOException，不onClose；资源真安全释放后onClose恰一次。内部区分OPEN_DIRTY/COMPLETE_SNAPSHOT/PUBLISHED/NEEDS_RECOVERY/CLOSED及published-cleanup-pending。PacketListener必须先真正停止生产者。
- initialScan登记Recovery映射后沿用原RestoreReplayGui(...,File)构造，File是物理恢复key不是logical授权；C4a不能调用C4b尚未实现的新构造。openRecovery未知key拒绝；legacy先安全复制/校验，恢复发布新的recovered成品，不覆盖旧原件；第一次save后第二次更新本handle自己的新成品。openRecovery所得handle的save/close**不自动删除或标记原Recovery已处理**；GUI两次save及安全close全成功后才discardRecovery(key)。用户放弃也用discardRecovery，但只标记/隔离保留原数据，不凭同名.del销毁；失败留待再次恢复。
- C4b接入：RestoreReplayGui→openRecovery/discardRecovery，保留两次save及读写次序；PacketListener保留C2 recordingFailure，awaitTermination false/interruption不得继续save/close，不提前no_recover；MarkerProcessor用open(input,input)及publishReplay→raw后才切读取路径；GuiSavingReplay用publishReplay或空名discardReplay，失败不一律关窗；FullReplaySender用owned resourcepacks temp、严格单段hex hash和两端try-with-resources；MetadataInjector→Replacement写候选/关媒体句柄/格式验证/commit，不截断原件；Lwjgl3Loader→owned temp jar、natives cache自有子区，已加载jar生命周期内保留；ReplayModRender→folders.getVideoFolder().toFile()，GUI显式视频文件仍允许；Image→owned temp png/finally安全删，清理异常不能覆盖原异常。
- 上述是task-5待实施契约，未写源码、未测试或磁盘实验。C4a正式交接时必须记录实际最终API；C4b不得把草案当现成方法盲调用。

## C3a Iris帧时钟最小落点（task-6静态实物结论）
- 可选 `@Pseudo @Mixin(targets="net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap=false)`。只加已批准的新文件和已有client配置注册；不加检测插件/依赖。`@Inject(method="beginFrame(J)V", at=@At("HEAD"), cancellable=true, remap=false, require=1)`，另 `reset()V RETURN` 清去重状态。目标/回调不引用Iris类类型；缺席由Pseudo跳过，有目标却不匹配不能require=0掩盖。
- 实物private字段为 `float frameTimeCounter`、`float lastFrameTime`、`OptionalLong lastStartTime`。beginFrame(r)计算 `((r-lastStartTime.orElse(r))/1000/1000)/1000.0F`，counter加dt后>=3600归零，保存r；reset将两float清零并OptionalLong.empty。只shadow lastStartTime，保留原算术不重写方法体。新增Unique状态仅handler owner、int logicalFrame、long lastCaptureNs。
- 单HEAD算法（r=Iris真实纳秒，c=handler此次finishTimeNano，f=RenderInfo.getFramesDone）：非capture且owner非null时lastStartTime=of(r)、清owner，再放行原方法（首普通帧dt0，counter不跳）；正常owner为空完全不干涉。capture且owner==handler且logicalFrame==f时lastStartTime=of(r)并cancel，保留该logical帧首面dt/counter。新logical捕获帧delta=(同owner ? c-lastCaptureNs : c-lastStartTime.orElse(c))，令lastStartTime=of(r-delta)，保存owner/f/c，放行原方法。原式算出delta，结束又保存真实r，无需两个HEAD排序/参数替换/返回状态框架。
- reset RETURN清owner及去重字段，尊重Iris自己的reset（PipelineManager.preparePipeline未缓存维度分支会调用）。首帧不擅自reset累计counter，世界时间主动倒退的负delta属于旧设置语义，不全局clamp。退出只转换一次真实基准，不能调用reset把counter清零。
- EntityRendererHandler仅在实际GameRenderer.render(counter,true)周围暴露currentCaptureNs/captureActive，try/finally及close清标志；只看handler!=null不够，无world/player时不置active。现有getRenderInfo().getFramesDone足够，不新增时钟类/ThreadLocal。Cubic/Stereo/Pbo每输出帧仅update一次后连续面/眼，IrisODS fakeInfo两次update仅推进一次且透传framesDone。不可只按timestamp去重（world-time暂停的下一输出帧应dt0）。当前VideoRenderer.drawGui不调用GameRenderer.render，不在面间插普通Timer帧。
- Iris独立COUNTER.beginFrame()先于Timer：本次只保留frameTime与frameTimeCounter，不冻结独立frameCounter，不宣称任意TAA/时域shader的多面状态完全一致。所有结论为实际remapped Iris1.8.6字节码/本地capture源码静态核验，未编译、未应用Mixin、未验证画面。

## C3b HUD回调精确落点（task-7，只读完成）
- 只改已授权 `de/johni0702/minecraft/gui/versions/mixin/Mixin_RenderHudCallback.java`：目标 `@Mixin(value=GuiLayerManager.class,remap=false)`，私有实例方法renderInner。Inject只写method="renderInner"（唯一稳定名）、remap=false、require=1；at=INVOKE target="Lnet/minecraft/client/gui/LayeredDrawer$Layer;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"，**At.remap=true必须显式**。
- handler `(DrawContext context, RenderTickCounter counter, CallbackInfo ci, @Local GuiLayerManager.NamedLayer layer)`，现有MixinExtras0.3.5按唯一类型捕获公开record（实物slot4，无需写死）。仅 `VanillaGuiLayers.DEBUG_OVERLAY.equals(layer.name())` 时执行；MinecraftClient当前 `options.hudHidden && currentScreen==null` 则return；最后原 `RenderHudCallback.EVENT.invoker().renderHud(context,counter.getTickDelta(false))`。
- 旧VanillaGuiOverlay.lambda$static$19自身无hideGui判断，但旧GameRenderer在HUD外层条件 `!hudHidden || currentScreen!=null`。新debug lambda$new$6却被!hudHidden子层wrapper门控，直接迁到它会丢“F1且打开screen”的原callback分支。新manager落点在每层Pre未取消检查后、wrapper的Layer.render之前；全局Pre取消则renderInner不进；F3开关不控制callback。此方案保留我们自己的旧门控，不抑制新引擎层事件。
- counter.getTickDelta(false)保留freeze时1和paused保存值，不从全局counter重取而丢capture counter。仍借用debug层现有DrawContext/Z，不重置矩阵、不硬编码深度。相对demo/effects之后、debug绘制之前、message/title/chat等之前；新原生层顺序/Z推进200及事件ID/类型本身与旧不同，不能宣称跨版本完全像素等价；新manager也无旧ExtendedGui逐overlay catch(Exception)，不额外吞异常。
- Loom1.7.435 NeoForge默认非legacy Mixin AP，实际TinyRemapper0.10.3已查CommonInjection.visitEnd/FirstPass.visit/At.visitEnd：父注解remap=false仍访问嵌套At，At显式true覆盖父值并重映射selector。method不写含Yarn类型完整描述符，避免外部NeoForge方法remap=false残留Yarn字符串。**D须核对remap产物selector确为** `Lnet/minecraft/client/gui/LayeredDraw$Layer;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V`，不把开发环境编译当作此验证。
- 实物证据为NeoForge20.4.234旧sources/named jar、21.1.249 named与universal字节码以及实际TinyRemapper，未改文件/脚本、未Gradle/运行。F3/F1+screen/事件取消/freeze/capture等仅静态分支核验，未当作已运行测试。

## C2.1正式完成与C3a启动交接
- C2.1子代理d0d4420b-b15d-4b89-9e7b-3f4019e6d9b9已正式完成，只写MixinClientConnection、PacketListener、ReplayModRecording、FullReplaySender四个已授权文件；未改PLAN/其它资源/构建/脚本/测试，未运行游戏或磁盘实验。FullReplaySender仅getReason→实际record accessor reason()。
- 首包实现：channelActive解码前装capture，从首LOGIN query或success开始有界完整前缀；256帧/8MiB/30秒限制，超过限额fail-closed，不截断；byte[]复制不retain或移动原ByteBuf reader。handlerAdded真实安装完成后交接，客户端任务屏障维持前缀/合成包顺序；解码对象身份凭据+原消息身份两层去重。ReplayModRecording启动finally必要绑定/失败清理；连接异常/关闭和禁用/超时释放缓存及待提交引用。保存/awaitTermination/no_recover仍留给C4，不能覆盖这些新关闭屏障或recordingFailure保护。
- 父mc_gradle compileJava在C2.1完成后运行19s，**失败127 errors/13 warnings**（仍只显示100；余27不可推断）。日志 `C:/Users/zhenz/.gradle/daemon/8.8/daemon-9716.out.log`。已显示100条中grep无C2八文件编译错误，已知disconnect错误消失；其余明确包括待做CameraEntity/Iris/render/jGui。不是完整C2通过或成品构建通过。
- 下一片**C3a**严格按前文25既有Java白名单，外加已批准新Mixin_IrisFrameTime及仅其render JSON注册，消费本计划task-4矩阵与task-6时钟实物交接。禁止写C1/C2/C3b/C4/PLAN/构建/测试/其它资源，禁止Gradle/游戏/磁盘实验，由父诊断。新旧capture/相机/手部/Iris语义都要保留，不能为编译用TODO、空实现或删除功能。发现白名单外阻塞先交接，不扩写。C3a正式完成后父编译，再开始C3b；C4a/C4b与独立D仍未开始。

## C3a正式交接与剩余GUI诊断
- C3a子代理7f616fd3-1432-42ac-abc5-46426368feb8已正式完成25既有Java+新Mixin_IrisFrameTime+唯一JSON注册，未运行Gradle/游戏/磁盘实验。实现task-4矩阵和task-6时钟契约；EntityRendererHandler提供isCaptureActive()/getCurrentCaptureNs()，仅实际GameRenderer.render期间生效，finally/close清除。逐帧counter固定capture partialTicks并快照两个duration，不额外推进时间。Cubic/stereo/roll使用真view矩阵；PathPreviewRenderer同步JOML及新版buffer；Iris包/目标/参数与帧时钟已迁移。
- 父mc_gradle compileJava诊断19s：**41 errors/13 warnings，仍失败**。完整错误已低于100上限；日志`C:/Users/zhenz/.gradle/daemon/8.8/daemon-1500.out.log`。全部41条落在既定C3b GUI/jGui白名单；不再有C1/C2/C3a编译错误。这只是当前编译诊断，不是Mixin应用、生产remap或游戏运行验证。
- C3b严格消费task-7 HUD精确落点；同时迁移原定15 Java与pack_format 34，保留原行为/公开契约。C4a/C4b及独立D仍待做。
- **两处Iris门控已获追加批准并实施**：用户在ask_user_question中批准render/rendering/Pipelines.java:110与core/ReplayModMixinConfigPlugin.java:42，仅两处LoadingModList检测字面量oculus→iris，父已各改一行，无其它逻辑改动。既有Java总白名单现为77个，另有已批准新Mixin_IrisFrameTime；这两个文件不授权其它重构。父修改发生在C3a正式结束/C3b开始之前；与C3b完成后一起编译验证。

## C3b正式交接与首次Java编译通过
- C3b子代理bc16856f-07e8-4108-be31-cdb775ad5d6a正式完成第218行15个既有Java及pack_format 4→34，未扩写范围，未自行构建/测试/游戏。新版Identifier/PlayerModelPart/StringHelper、Tessellator/BufferRenderer迁移完成；HUD严格按task-7实现，jGui GameRenderer/MouseListener锚点实物核实；保留旧背景不透明泥土语义与BackgroundRendered事件，公开接口不变。
- 父mc_gradle compileJava **BUILD SUCCESSFUL in 30s，14 warnings，无编译错误**；日志`C:/Users/zhenz/.gradle/daemon/8.8/daemon-3436.out.log`，仍使用-Djava.io.tmpdir=C:\\Temp。此前父两处Iris检测改动也包含此次编译。此为全源码Java编译通过，不是remap/build/test/运行通过；D仍须独立完成。
- 现在按既定顺序进入C4a，仅Setting/ReplayFoldersService/ReplayFilesService/ManagedReplayFile四个基础文件，消费前述task-5接口与所有权/发布/rebase/recovery契约。C4b九个调用方须等C4a正式完成并审阅/编译后启动，尤其PacketListener必须保留task-8 C2.1屏障，不抢先修改。C3a/b已结束，不再有其写入者。

## C3a实际补丁只读复核（task-9/10完成）
- task-9矩阵/注入与task-10 Iris实物复核均未确认新的C3a迁移缺陷。实际代码的slot14 view、roll/cube/stereo左乘、projection、preview栈、粒子render目标/恢复及其它锚点落实task-4；捕获counter不推进timer。Iris active范围、owner+framesDone去重、reset/退出重基准、首次偏移/负delta/原生量化落实task-6，uniform三参/双目录redirect/手部实例调用ABI正确；父两处iris检测修正亦核验。审查均只读、无构建/游戏/磁盘实验，不替代实际Mixin应用或画面验证。
- D在生产remap产物中除task-7 HUD selector外，还须核对Iris手部selector为`Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;`，不得残留Yarn名。Iris缺席启动、多面画面仍未实测；帧时钟仅保证frameTime/frameTimeCounter，不保证独立frameCounter/TAA或任意shader跨面时域一致。
- 确认但本轮不扩修的迁移前旧缺陷：MixinParticleManager.java:45–49在全景粒子方向恰为(0,0,-1)时cross=0且1+dot=0，零Quaternion.normalize产生NaN；finally当前已正确恢复，不永久污染相机。git diff确认该方向算法未被C3a修改，非迁移回归，也不属于本轮路径/必要迁移修复目标。若后续另行修复，已核实JOML rotationTo有反向保护，零方向须保留原rotation；本轮不得悄悄扩改。

## C4b衔接C2.1关闭屏障（task-8只读完成）
- 本轮仅核验PacketListener全文件及ReplayModRecording启动finally，未实证新的C2.1自身阻断/丢包。以下是C4待实现留项，不能误记为已完成或C2.1回归；不宣称外部异步资源协作者全部终结。
- 必须保持入口：ReplayModRecording.initiateRecording finally→finishLoginCapture；handlerAdded真实定位raw后才handOff/completeBootstrap。channelInactive→stopRecording先capture.close，再packetQueueLock内一次性closing=true/handlerReady检查/mc.send客户端fence。锁序capture→packetQueueLock；不得反向同步取capture锁，reportRecordingFailure对capture.fail仍只异步调度。
- 已接受的逐帧PendingPacket客户端任务先于fence；其lambda故意不因closing拒绝，不能提前release/改成后台批量drain。token.packet=null表示转移/取消所有权，不只是Set删除。fence继续drainBootstrap→finishRecording；最终lastSentPacket/duration在客户端确定，最终metadata允许在closing后专门入队，随后封住新保存任务。保留connectionEventHandler.getPacketListener()==this的reset身份保护。
- 正确后台链：正常saveService.shutdown（非shutdownNow）→awaitTermination确实true→packetOutputStream.close→检查recordingFailure及close结果→才可Marker判断、save或raw saveTo。writePacket任务不持replayFile monitor，外层synchronized不能替代executor终结。false/interrupt绝不能fall-through关流/save/close/发布；中断恢复interrupt。超时若希望排空已有任务，不要先reportRecordingFailure，因为它会令剩余writePacket释放并跳过包；可本地记录等待失败/保源，确认真正静止后仅恢复收尾，不把超时分支继续说成成功录制。未静止保留work/句柄/锁，不抢跑Managed.close。
- await true只证明任务终结，不证明成功。saveMetaData和addMarker的IOException/RuntimeException须进入首失败锁存，不能只log；未消费Future不得吞错误，可用本文件private统一任务包装，不保留无限每包Future。外层submit/拒绝亦处理；closing后普通metadata/marker先拒收，最终metadata是唯一专用封口任务。lastSaveMetaDataId只能为已接受任务递增，避免拒绝的新id使最后合法任务失效。保留单线程FIFO和既有包失败/引用释放。
- packetOutputStream.close错误须reportRecordingFailure，关流后再查失败。保存/Managed关闭/后处理失败统一错误UI，保留首因，后续错误suppressed或日志，不走presentRenameDialog；不要finally硬关。已静止失败handle可尝试Managed安全close生成独立恢复快照；失败仍保留work/锁。
- raw无输出分支：移除预先gui.close及裸.no_recover创建/删除；候选exists仅优化，以saveTo CREATE_NEW的FileAlreadyExistsException决定换后缀，其它IO不盲重试。raw saveTo成功→Managed.close成功→才关UI返回；raw导出不rebase、不改logical outputPath。正常分支save成功且rebase→close成功→MarkerProcessor.apply(outputPath)/原路径→rename UI。工作区清理仅服务所有权/提交状态授权。
- 必须保留EarlyLoginCapture/LoginFrame、LOGIN_CAPTURE/LOGIN_RECORDER_KEY、限额与首decoded/inFlight身份凭据、install/observe/finish/close/reposition/handOff；packetQueueLock/packetQueue/queuedBytes/pendingClientBytes/handlerReady/bootstrapComplete/closing/pendingClientPackets/PendingPacket/loginCapture；save两重载/completeBootstrap/dispatchPacket/drainBootstrap/releaseQueuedPackets/writePacket/stopRecording；recordingFailure首因及report逻辑；outputPath/replayFile/metaData/时间字段、marker公共签名、resourcePackRecorder访问。无需新增public PacketListener接口或改ReplayModRecording。

## C4b资源包回调屏障补充（限定只读静态证明）
- 已核实1.21.1 ServerResourcePackLoader使用client::send，Loader$4的downloadAsync.thenAcceptAsync投递客户端队列；MixinDownloadingPackFinder.onDownload HEAD直接同步调用ResourcePackRecorder。故排在stop fence之前的下载回调整次index→pack写入先完成，不能把它误称下载线程竞态。
- 后置窗口已静态证实：ReplayMod.runLater:218–219转scheduler；SchedulerImpl:54–85/95–121的两个分支都不内联、投递独立ReplayModExecutor或render队列，与mc.send无跨队列FIFO。MC队列可为[fence,download callback]，fence将reset投到另一队列并启动保存线程，后续callback仍取得旧listener；旧ResourcePackRecorder先锁内index后锁外pack，保存线程可插入二者间。该回调不归saveService管辖，正确await true也排除不了。此是实际源码/字节码允许序列，不是运行复现，不将其归为C2.1新增回归。
- **C4b在已授权PacketListener内封闭该窗口**：构造ResourcePackRecorder私有子类，覆写既有public recordResourcePack(Path,int)入口；保持原UUID→int查找时点，不将super.recordResourcePack(Path,UUID)整体延迟到worker。用与关闭相同的packetQueueLock进行closing/recordingFailure/任务准入检查与提交；closing后先拒收，不触碰index/pack。已经成功准入并提交的任务，worker不能因随后closing=true就跳过，必须计入正常executor drain。保持现有对外ResourcePackRecorder访问接口，不改未批准Recorder或Mixin。
- 不能仅排队super的int方法：它会吞IOException。必须在PacketListener私有实现执行源读取/hash/pack完整写入并关闭→再更新index，所有读/写/close/submit异常进入首失败锁存。复用实际既有ReplayFile API且先查签名，不新增public API。不得随意给UUID入口加嵌套同步而引入recorder monitor→packetQueueLock逆序；只做本次安全保存必要的队列串行化。
- 源Path延迟读取的生命周期必须明确：可提前取得独立bytes，或在worker读取失败时锁存录制失败并保源；不得假设受Minecraft管理的源Path永不移除，也不得删除它。以同一任务写pack+index，pack成功关闭才提交index，避免产生指向未写完资源包的成功索引。
- 屏障仅承诺**成功通过PacketListener准入并提交saveService的资源包保存任务**，不承诺等完所有网络下载；未完成下载、未准入回调及closing后到达的回调不在保证内。原task-8关于未审外部协作者的泛化限制仍适用，但本条具体回调已核验。

## C4b调用方顺序细分
- 为隔离PacketListener千行协议/引用计数与保存逻辑，C4b在C4a正式交接后分两片串行完成，不扩大原13文件授权。
- C4b1：仅其余8文件——core/gui/RestoreReplayGui.java、recording/gui/GuiSavingReplay.java、editor/gui/MarkerProcessor.java、replay/FullReplaySender.java、render/metadata/MetadataInjector.java、render/utils/Lwjgl3Loader.java、render/ReplayModRender.java、de/johni0702/minecraft/gui/versions/Image.java（前三组路径相对src/main/java/com/replaymod，最后相对src/main/java）。消费C4a真实公共API，落实恢复二次保存与safe-close后才discard、raw/no-clobber发布、所有权临时文件和安全替换；禁止改PacketListener或基础4文件，阻塞交接父处理。
- C4b2：C4b1正式结束并父审阅/编译后，仅PacketListener.java按task-8及本节资源包准入补充完成；不改另外12文件、C2.1 Mixins/ReplayModRecording或任何未批准文件。保留捕获/排序/引用计数合同；先核实C4a与C4b1实际API，再改调用链。之后才进入独立D。

## C4b1实际交接与父审阅（进入C4b2的前置已完成）
- C4b1八文件正式交接并停写：Restore通过openRecovery、两次save及checked close后才discardRecovery；拒绝只隔离。SavingGui通过publishReplay/discardReplay，失败不关UI，成功行不重做。Marker先关闭原句柄，再publish raw且改用返回的raw输入；已存在work用open(in,in)、分片open(null,out)、close成功后才加入GUI结果。
- MetadataInjector仅Replacement候选输出，DirectFileReadDataSource/checked close、全部mdat摘要/全部chunk arrays/原UUID布局验证后commit；task-15只读实码复核无已证实新阻塞，FreeBox.getData实际duplicate().rewind()已核实。仍拒绝碎片化/外部媒体引用/不支持结构或媒体搬移，不宣称任意MP4兼容。
- Lwjgl临时JAR归属登记，classloader使用期间保留、checked close后清理；native仍配置cache。Image PNG走owned temp且finally保留主异常。Render默认File目录来自冻结resolver，构造/字段无新增早期根访问；GUI显式输出不变。
- FullReplaySender：40位hex/SHA-1实际校验/CREATE_NEW/双流关闭/归属目录/退休后阻止队列中的provider提交。父修正其eager temp为首次资源包提取时在resourcePackLock内创建；未创建时retire直接返回；没有资源包的回放不触碰temp卷。
- **有意保留的安全清理限制**：凡交给Minecraft provider的资源包文件保留并警告，不自动删除；只自动回收从未交出的owned目录。task-16本地实物证明remove/clear是排队，getPackLoadFuture只报告APPLIED，Loader.close仅关Downloader；ZipFileWrapper.close后仍能重开，通用重载成功/关机事件/MC.close尾也没有路径级不重开及全部任务终结证明。本次不补猜测性hook或扩大源码范围。不能声称这些文件已在停止回放/游戏退出时自动回收；交付须说明确认所有相关进程结束后按日志仅清理对应资源包自有目录，不清空共享temp根。
- 父首次compileJava成功31s/7个旧弃用警告；lazy修正后再次成功24s/2个旧EventBusSubscriber警告，均1 task executed。不是全build/remap/test或游戏实测。父审已读恢复/GUI/Marker/temp loader/Image/Render/FullSender关键实际调用链，C4b1正式结束；可串行启动C4b2。
- C4b2只写src/main/java/com/replaymod/recording/packet/PacketListener.java；不写PLAN、其它源码、测试/脚本/配置、Gradle、资源或依赖，不运行构建/游戏/磁盘实验。读取本文件上面的C4b关闭屏障/资源包回调合同及下面C4a实际API，必要实际jar只读查签名。交父精确变更/疑点并正式停止源码写入后父编译，之后才D。

## C4b2实际交接与父审阅（C阶段正式结束）
- 专用C子代理ce56645b-b75c-4301-88b3-60c869e15c73完成并正式停止写入，仅PacketListener.java。保留C2.1捕获/锁序/token所有权/逐包FIFO；新增同队列任务准入、saveTasksClosed封口、最终metadata特权提交、私有QueuedResourcePackRecorder int覆写。UUID查id时点不变；resourcepack源读取/hash/pack写入并关闭/index提交在同任务，读源失败锁存且不删Minecraft源；只保证已准入任务，不等待所有下载。
- metadata真实ReplayMetaData复制构造会遗漏protocol，已按实际jar补齐；worker先取queue锁核对已成功准入ID，避免execute先运行/ID尚未提交的竞态。metadata/marker/资源包IO和RuntimeException/任务拒绝进入首失败锁存；无未消费Future。普通任务closing后拒收，已准入任务不因closing跳过。
- 客户端fence最终duration→最终metadata一次→封口→正常shutdown；身份保护reset保持。后台仅awaitTermination==true后关packet流/检查失败/保存。10秒false或中断只错误UI返回，不锁存为packet失败、不强关/发布/close、不补晚到成功；中断恢复标志。**此实现未做晚到终结收尾，句柄和进程锁保留，即使之后排空也可能需要重启恢复**；必须在交付限制中明确。
- 静止失败只尝试Managed安全close，失败保留；非Managed不冒险关。raw saveTo CREATE_NEW，仅FileAlreadyExistsException换后缀，saveTo和close全成功才关UI，不再裸.no_recover。普通save/rebase→读规范化metadata→close→后处理/rename；后处理或UI失败不冒充成功。父把错误报告标题改成Saving or post-processing replay file，避免已发布后的后处理异常也误称全部为未发布work。
- 父已读任务准入/metadata ID/资源包任务、逐包dispatch/write、stop/fence、完整finishSavingReplay、失败收尾、marker/decoded players关键链；compileJava成功24s、1 task executed、2个旧EventBusSubscriber警告及PacketListener deprecated提示。无全build、测试、游戏/磁盘实测；C4b2正式完成，可进入D。

## 用户确认的收尾修复与D复验交接（最新状态）
- 用户经ask_user_question明确选择“修复 Mixin；EXR 仅记录限制，不追加修改”。EXR缺lwjgl.jar保持不改、不另补资源/依赖或重构loader。YouTube例外同样仍保持。
- 父已仅在Mixin_ForceChunkLoading.java替换5处标识符：field_45614→chunkBuilder三处，field_45615→chunkRenderingDataPreparer两处；保留类型/private/@Shadow/@Final/控制流。修改前读过完整文件且实际named/production mapping已核实；git diff真实显示该5处以及此前已批准RenderTickCounter迁移，diff --check通过，无其它本轮源码修改。所有子代理此前均正式停止，父此次修复结束停写。
- 交D复验：消费本PLAN，按前文mc_gradle clean build参数重新构建当前源码（上次成功不覆盖这5处），不可跳test；核两条旧field remap警告消失，可能剩两个已有Keyboard候选warning。原测试41全过；核最终非dev JAR的@Shadow声明和getfield变为sectionRenderDispatcher/sectionOcclusionGraph且无旧字段，重新报大小/SHA256和真实回执。复用此前完整归档/依赖/主链审查，不扩大修源码/配置/测试/脚本/依赖/游戏/磁盘实验；EXR已明确排除，应报告为已知功能限制，不再因它无限阻止本次范围内完成。实际运行/ImDisk仍未验证，不能宣称完整游戏兼容或EXR可用。结束明确停止写入/构建。

## 独立D恢复正式结论（2026-11-07）
- 恢复D 1d46dd22-f084-443c-86d0-dbfd3c5e9cca已正式结束并停止全部写入/构建；实际本轮零修改且未重复构建。已恢复旧D原mc_gradle clean build回执，daemon19628确认1m1s、9tasks全执行、daemon exit0；所有当前构建输入修改早于04:06:37UTC产物，最新源码2026-11-07 04:03:22UTC。实际Java21.0.12.1/Gradle8.8/Loom1.7.435/NeoForge21.1.249。
- compileJava/processResources/jar/processIncludeJars/shadowJar/remapJar/compileTestJava/test/check/build全部完成；processTestResources/validateAccessWidener NO-SOURCE，remapSourcesJar SKIPPED。14编译warnings和4 remap warnings保留。XML为41pass/0fail/0error/0skip，仅既有SPTimelineTest，无测试diff。
- 当前仅候选非dev JAR：build/libs/reforgedplaymod-1.21.1-0.3.jar，18591074bytes，SHA256 f67f05a77625b969916d6c5c2772ebbab68f2ddc958f8f4eb1ae5bb80748fb0b。两mod版本0.3/2.6.18、依赖范围、pack34、10mixins/登记类、ReplayStudio及transitives、12JarJar均核实，主及nested CRC正常、主无重复entry、Iris未打包。实际refmap为0，不虚报配置声明的3种文件已存在；非legacy AP直接remap已核HUD/手部selector。Fabric旧元数据保留不能宣传多平台。
- **不批准当前候选为可交付成品**：ForceChunkLoading旧Shadow两字段在named/生产目标均缺失，生产mod仍旧名，无替代，独立D确认静态Mixin应用阻塞。父准备向用户确认只改已批准源文件5个标识符（2声明+3引用），不改类型/Final/行为；需修后重构建并由D复核。
- 额外原有缺项：VideoRenderer→EXRWriter.create→Lwjgl3Loader.getJarFile要求同包lwjgl.jar，但项目/主JAR/全部nested均无该资源，EXR分支会缺资源报错。原HEAD已同要求，不归为此次存储回归；不擅自补资源/依赖/改EXRWriter。父向用户确认是否维持原范围并明确EXR限制，或另批方案后处理。
- D重审主存储错误链未确认更多确定性主存储缺陷；无游戏/跨盘/ImDisk/离线/满盘/断电实测。前文所有资源包/超时/恢复扫描/进程锁/YouTube/MP4/Iris/既存粒子/restrictions限制继续有效。

## D恢复期间父只读发现（2026-11-07，未修改源码）
- Harness恢复后原D UUID已无法send_message，catalog仅durable audit teammates；无java.exe进程、无后台job。已串行恢复D 1d46dd22-f084-443c-86d0-dbfd3c5e9cca，要求先复核已有产物避免重复构建；父不写源码。
- 已有daemon-19628.out.log:197–200有4条Mixin remap warning，208成功1m1s/9tasks executed（2026-11-06T21:06:41-0700）；XML timestamp2026-11-07T04:06:40，41/0/0/0。daemon7932只是旧tasks列表，非build。不能只凭成功忽略警告。
- 父实际named MC1.21.1/NeoForge21.1.249 jar的javap：WorldRenderer字段实际为private ChunkBuilder chunkBuilder及private final ChunkRenderingDataPreparer chunkRenderingDataPreparer，**没有field_45614/field_45615**。当前已批准Mixin_ForceChunkLoading.java仍在38/40 shadow旧名、63/102/109引用旧名。这不是编译检查得出的安全结果，需D核生产JAR/映射确认；可能阻塞Mixin加载。若修应仅该已批准文件旧字段名替换真实named字段名、保持类型/访问/Final/逻辑，不顺手重构；先父/子交接确认不并行写。
- Keyboard两警告不同：named Keyboard无method_1458/method_1473，但确有lambda$charTyped$6(Screen,int,int)、lambda$charTyped$7(Screen,char,int)。现MixinKeyboardListener各组已有对应remap=false替代项，Group min=max=1；该文件未授权修改，不因两个可选候选缺失臆改。D核实际required/defaultRequire与组校验即可。

## 独立D交付核验指令（前序A→B→C现已完成）
- D消费此PLAN及实际文件，先确认平台/构建/库与skills。父后续仅文档/只读，不与D同时写源码。允许D在既有明确白名单内修小编译错误；不得动其它来源/测试/脚本/配置/部署文件/Fabric元数据/vendored jars/全局环境/Git历史；范围外或语义大修先报父。不得runClient/runServer、安装依赖插件、挂载/格式化/实验RAM盘或运行压力测试；不要sandbox_permissions。
- **完整验证用mc_gradle**：projectDir=C:/Users/zhenz/ReForgedPlay，task=clean，args含build、-Djava.io.tmpdir=C:\Temp、-Duser.language=en、-Duser.country=US、--no-daemon、--console=plain。clean仅项目标准build产物，避免旧mods.toml或旧classes残留；先确认没有被改为外部buildDir。不能跳测试、不能shell gradlew。若失败先报告准确错误并按范围修/交接，不冒称完成。
- 独立审阅重点：最终六根/读写恢复清理闭环、C4a/C4b1/C4b2契约与错误路径，迁移的网络状态/引用、Mixin精确目标及Iris可选门控；复用此前经验证的证据，不扩大未批准源或为了绿构建删除功能。没有游戏运行，不把compile/build当Mixin启动实证。
- 检查clean build中的compileJava、processResources、shadow/remapJar、compileTestJava/test/check/build真实结果；读取现有JUnit XML确认实际pass/fail/skipped数量（现有SPTimelineTest原41测试，不能写新测试）。test只覆盖原有时间线，不能称存储故障/ImDisk/跨卷已实测。
- 核实最终可安装非dev、非dev-shadow的JAR绝对路径/大小/SHA256；真实archive中neoforge.mods.toml版本展开、两mod条目/依赖范围/10mixins及refmap、pack_format34、关键新类和ReplayStudio已打包/必要transitives与JarJar存在，Iris不应打包。源码Java21不自动证明所有runtime兼容，报告不确定点。可用现成jar/javap/unzip只读检查，不增加脚本/源码/测试。
- task-17已完成且零修改：NeoForge21.1.249 POM/module/userdev/installer发行声明均为sponge-mixin0.15.2+mixin0.8.7、ModLauncher11.0.5、ASM9.10.1。ModLauncher实际manifest与Launcher/模块包定义链确认API spec9.0；MixinService最小JAVA_16，全局level仅上调，低于最低的JSON8不令其降级，旧config升级允许。MixinInfo.State.validateClassFeatures按LanguageFeatures.scan/global supports判断，JAVA_16与JAVA_21 feature mask相同；MixinTargetContext.requireVersion只在major>ASM上限硬拒绝，ASM9.10.1最高V27=71含V21=65，JSON声明较低只日志。实际binary和匹配source交叉核对。因此不能以65>52认定拒载，不改JSON；仅排除这个特定推断，不证明启动/最终classpath/Mixin注入成功。
- 最终回报：精确构建/测试/制品证据、独立审查发现与修复范围、尚未验证的实际限制，并明确已停止写入。保留YouTube转码临时文件范围外、provider已接收文件不自动清理、超时可能重启恢复、RAM盘掉盘断电不可挽救、当前恢复扫描/跨进程限制。四种restriction行为未实现、既存ParticleManager零四元数、其它旧警告不能隐瞒或未经授权顺手修。

## C4a实际交接与父审阅
- C4a正式结束，仅改Setting/ReplayFoldersService/ReplayFilesService/ManagedReplayFile四文件；父compileJava成功22s，daemon-18752.out.log，增量9个旧弃用警告；不等同全量build、remap、测试或游戏验证。
- Folders保持构造(SettingsRegistry)及六个Path getter：getReplayFolder/getRecordingFolder/getRawReplayFolder/getCacheFolder/getTempFolder/getVideoFolder，均throws IOException；getCachePathForReplay/getReplayPathForCache也为IOException。六根首次业务读取一次冻结，默认temp继承构造时java.io.tmpdir，空work/raw从finished派生；相对gameDir解析；拒绝危险根和非默认允许的包含/重叠；不会为单一getter创建其它根。
- Files保持open(Path)、open(Path,Path)、initialScan(ReplayMod)。新增：Path createTempFile(String,String)、Path createTempDirectory(String)、void deleteOwnedTemp(Path)、Path publishReplay(Path,Path)、void discardReplay(Path)、ReplayFile openRecovery(Path)、void discardRecovery(Path)、Replacement prepareReplacement(Path)，均throws IOException。Replacement public static final implements AutoCloseable，提供Path stagedPath()、void commit()/close() throws IOException。
- Temp返回独占容器内的已创建条目；deleteOwnedTemp仅接受本服务登记的确切条目，先关闭流。publishReplay返回绝对目标，CREATE_NEW、不自动改名、不覆盖；只清理登记且身份未变的源，未知源仅复制。discardReplay拒绝活动handle/未知文件。
- open(work路径)表示新录制、存在拒绝；编辑已保存work必须open(input,input)。open(null,out)恒CREATE_NEW；input非空必须存在。同handle save后可再次保存自己的输出；saveTo导出不rebase，目标等于logicalOutput走save。save后重建新物理generation；旧代流失效；快照revision跟踪不因cache dirtiness失效。
- 锁仅进程内，覆盖逻辑源/目标、恢复、导出、替换及已知文件别名；不保证跨JVM/恶意路径竞态/断电耐久性。public ManagedReplayFile(ReplayFile,Runnable)签名保留但无事务上下文的save/saveTo/close明确拒绝；应用必须files.open工厂，不宣称第三方行为兼容。
- 恢复key是已登记物理workspace或legacy .mcpr.tmp，不是任意最终目标；openRecovery复制并使用独立原名-recovered-UUID.mcpr；save/close不删除原key。GUI两次save及safe close全成功才discardRecovery；拒绝恢复只隔离标记。torn latest receipt不回退旧snapshot，保留人工处理。
- Replacement stagedPath已创建、在目标卷；原件只读且保留直到commit，进程目标锁至close；commit复核原件SHA/身份、备份后发布，未提交/失败close保留材料；成功后清理失败仅警告。调用者必须完成媒体验证且关闭所有读写句柄才commit。可直接只读原件，不强制整视频复制进RAM temp；不保证外部进程修改无法发生，提交复核是必要防线。
- task-13只读审查：多次save/rebase/saveTo、revision、publication receipt、Replacement备份、恢复来源隔离及释放主链未发现确定性新增缺陷。非Windows仅hard-link、不支持则保源拒绝符合原PLAN:189；Windows/ImDisk不依赖硬链接。构造内部私有句柄异常的constructionUncertain保留锁到重启，所以rebase重试仅覆盖普通前置IO失败，不能泛称所有异常可立即恢复。
- task-13发现cache流write/flush/close错误永久进入录像streamFailure，导致本体未改的handle永远无法close；父已最小修正TrackedOutput.failed：仅!cache时锁存streamFailure。cache错误仍原样抛出；close失败仍留outputs并阻止本次释放；之后成功关闭可继续安全释放。真实录像错误不清除。父再次compileJava成功26s、1 task executed，已覆盖该修正；此次增量无编译警告输出。

## C3b额外只读审查（task-11）
- 七个复杂GUI/HUD文件未发现确定性新增缺陷：GuiLayerManager注入context/tickDelta(false)、GameRenderer drawScreen前后hook与矩阵恢复、Mouse method_55795实例参数与取消链、buffer顶点格式/隐式结束、model-view和position矩阵各用一次、timeline法线/Z/scissor finally、旧dirt背景及BackgroundRendered一次事件。
- 仅源码/实际字节码证明，不代表remap、Mixin启动、鼠标、F1/F3、暂停或视觉运行验证；浅替换未逐项审计，旧粒子零四元数NaN仍是独立未授权留项。

## task-14覆盖审查及明确范围决策
- 其余稳定src检索发现唯一新增遗漏：extras/youtube/YoutubeUploader.java:130–148为球面非MP4转码在原video旁创建时间戳.mp4，上传后242–245删除，属于项目临时件但绕过tempRoot。此文件不在批准范围；父已核实源码并询问，用户明确选择“不追加，保持原范围”。不得擅改；最终交付须明确保留YouTube转码临时目录例外，不声称所有临时路径覆盖。
- 未发现第二处所查旧recording/cache/raw固定路径或有效全局temp遗漏。第三方隐式缓存、配置/crash/screenshot/export.log和GUI显式输出不冒报为六根业务遗漏。检索排除当时正在写的C4b1八文件及待C4b2的PacketListener。
- 初始化前态静态链闭合：Core构造末尾SettingsRegistry.register→Backend.load(true)，其后construct→initModules→initClient/initialScan；Folders构造仅保存gameDir/startup temp而不冻结。父确认Files:25–33字段仅集合/Logger、构造只保存folders；稳定已编译Render构造无路径getter。C4b1正式交接后父已读当前Render:25–48/68–78，字段/构造/事件登记未新增早期根访问，仅getVideoFolder:51–57改用resolver。不是游戏运行验证。

## C4b1 MP4实现约束（task-12最终收口，取代过严中间建议）
- 实际依赖com.googlecode.mp4parser:isoparser:1.1.7；已读解析JAR。IsoFile.getBox只写、不关资源；IsoFile.close关DataSource；Box无close。必须先建立DataSource资源所有权再new IsoFile，覆盖构造异常；输出force及输入/输出/验证资源的checked close全部成功后才Replacement.commit。禁止任何对原videoFile的输出/截断。
- 已核实DirectFileReadDataSource(File) implements DataSource，可传IsoFile(DataSource)；map用readFully+byte[]而非mmap，mdat transfer分块，不是整视频读入内存。优先使用它，勿将FileDataSourceImpl.close关channel误当unmap；未复现Windows锁定，不声称必然失败。
- 保留旧UUID布局：new UserBox(new byte[0])+UUID_BYTES/XML实际输出正确16-byte UUID、XML、16尾零；重解析getUserType()==UUID_BYTES、getData()==XML+16尾零。不得误报缺UUID或无批准改为另一种格式。
- BasicContainer按列表输出；MediaDataBox原源offset/size transfer；stco/co64不重定位。先完整枚举原件，记录边界/总长度/top-level布局、每个mdat起点和大小、全部chunk数组快照、目标trak；缺必要结构或未支持的碎片化/表格式拒绝注入且保原，不宣称任意MP4支持。
- 用实际moovAfter-moovBefore的long增量Δ（包括祖先32位阈值header增长），不是仅新UserBox大小。free可用F按实际data.limit，R=min(F,Δ)，新free payload=F-R，检查溢出与安全int转换。按修改后各top-level getSize累加预测实际输出位置及expectedLength；原对象getOffset仍旧坐标，不能用于证明新位置。
- 不再要求free不足一律失败或总长固定：若所有mdat位置/大小、所有stco/co64值保持，可允许明确预计的文件增长；mdat在前、moov在后、free容量0是合法窄路径。若处理导致mdat移动则拒绝，不添加通用offset重定位/搬移free策略。总长相同本身不充分。
- 候选getBox、force、checked close后，用非mmap source重解析：完整边界/expectedLength、每个mdat实际offset/size、chunk表逐值、目标trak中的预期UUID和旧布局payload；建议分块比较/摘要媒体字节，不整视频入内存。所有验证资源正常close后commit。异常或不支持必须报告未注入/原视频保留；仅清理自有临时项，不擅删Replacement保留的恢复材料。

