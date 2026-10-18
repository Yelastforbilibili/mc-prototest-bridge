# mc-prototest-bridge

**给 Minecraft 机器人的「服务端真值」通道。**

同一个想法的两半：

| 组成 | 是什么 | 回答什么 |
| --- | --- | --- |
| [`prototest-bridge/`](prototest-bridge) | Paper 26.3 服务端插件 | 「**服务端**到底认为发生了什么？」——原版方块 state id、碰撞盒、玩家/实体状态、容器内容，以及一份「真的到达服务端」的事件日志，全部走本机 HTTP/JSON。 |
| [`mc-bridge-dsh/`](mc-bridge-dsh) | DSH 插件包 | 把上面的 HTTP 暴露成 agent 工具（`mc_server` / `mc_watch` / `mc_bridge`），让 AI 可以**观察**服务端，而不是猜。 |

写或调 Minecraft 协议客户端，大部分时间是在猜：代码认为自己挖掉了方块、打开了箱子、打到了实体，
但客户端这边没有任何东西能告诉你这个包格式对不对、服务端收没收到、还是被静默丢弃了。
这个项目让对话的另一方开口。

最初为 [`mc-proto-bot`](https://github.com/ImWuMie/mc-proto-bot)（纯 asyncio、零依赖的 Python
Minecraft 客户端）而建，但本身与具体客户端无关：任何正常连到 `127.0.0.1:25565` 的机器人，都能被观察。

```
┌──────────────────────┐   Minecraft 协议 (25565)   ┌───────────────────────────────────┐
│  你的机器人 / 客户端  │ ─────────────────────────► │  Paper 26.3 服务端                │
│  (Python / Java / …) │ ◄───────────────────────── │  + prototest-bridge 插件          │
└──────────────────────┘                            └───────────────┬───────────────────┘
          ▲                                                         │ HTTP/JSON
          │ 断言：客户端认为的  vs  服务端真值                        │ 127.0.0.1:25963
          ▼                                                         ▼
   ┌──────────────────────────────────────────────────────────────────────────────┐
   │  GET /block  →  {"stateId":1,"collision":[[0,0,0,1,1,1]],"data":"minecraft:stone"}
   │  GET /events →  [{"seq":42,"type":"break","block":"minecraft:oak_log", …}]
   │  POST /setblock、POST /command、GET /registry/blocks（26.3 全部 35723 个状态）
   └──────────────────────────────────────────────────────────────────────────────┘
                                        ▲
                                        │ HTTP
                              ┌─────────┴─────────┐
                              │  DSH: mc-bridge   │ → mc_server / mc_watch / mc_bridge
                              └───────────────────┘
```

## 目录

- [为什么需要它](#为什么需要它)
- [快速开始](#快速开始)
- [桥能回答什么](#桥能回答什么)
- [两个插件](#两个插件)
- [方块 state id 与 section 解码](#方块-state-id-与-section-解码)
- [一次完整的验证流程](#一次完整的验证流程)
- [仓库结构](#仓库结构)
- [构建](#构建)
- [兼容性与限制](#兼容性与限制)
- [常见坑](#常见坑)
- [实测发现](#实测发现)
- [License](#license)

## 为什么需要它

协议客户端有两张脸，只有一张能从内部测试：

* **它发出的东西** —— 可以单测：拼字节、比对 hexdump。
* **服务端的裁决** —— **无法**从客户端测试。服务端接受了 `ServerboundPlayerAction` 吗？方块真的变了吗？
  容器开了吗，还是因为窗口 id 过期被拒了？聊天消息到了吗，带的是哪个签名？

通常的答案是「看服务端控制台，用眼睛确认」。这没法变成断言，因为控制台打印的是**给人看的文本**，
不是**状态**。

这个桥从服务端进程内部、以机器可读的状态回答：

* **原版注册表真值**：插件不自带方块表，而是读 `Block.BLOCK_STATE_REGISTRY`（服务端序列化区块用的
  同一个 `IdMapper`），所以 `stateId` 必然等于线网上的数字。`GET /registry/blocks` 导出 26.3 的
  **35723** 个状态：`id<TAB>minecraft:名字[属性]`。
* **服务端算出来的碰撞盒**：`GET /block` 返回真实的 `VoxelShape` 盒，可直接检验客户端物理模型
  （`oak_stairs[facing=north]` 是两个盒，`short_grass` 没有盒）。
* **真的到达了服务端的事件日志**：30 种事件 —— `break`、`place`、`interact`、`interact_entity`、
  `attack`、`swing`、`consume`、`drop`、`pickup`、`click`、`chat`、`command`、`death`、`respawn` ……，
  每条带单调递增的 `seq` 游标，「我的包落地了吗」= 一次 HTTP GET。
* **可控的世界**：`POST /setblock`、`POST /fill`、`POST /command`、`GET /tick/wait`，
  测试可以先搭夹具、精确等 N tick，再读服务端的答案。

## 快速开始

### 1. 一个 Paper 26.3 测试服

任何 Paper 26.3 都行。让机器人免验证直接进服的最小配置：

```properties
# server.properties
online-mode=false
enforce-secure-profile=false
allow-flight=true
spawn-protection=0
difficulty=peaceful          # 怪物在测试中途打死 bot，能白白浪费一小时
spawn-monsters=false
level-type=minecraft:flat
```

`peaceful` 很关键：怪物开着时，史莱姆可能在你两步测试之间把 bot 打死，
而服务端会忽略死人的大部分数据包 —— 这就是经典的「有时能行、有时不行」。

### 2. 装插件

```powershell
cd prototest-bridge
pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' -Deploy
```

`-Deploy` 会把 jar 拷进 `<server>/plugins/`。启动日志出现：

```
[prototest-bridge] prototest bridge listening on http://127.0.0.1:25963
```

```bash
curl http://127.0.0.1:25963/status
```

### 3.（可选）agent 工具

```bash
cd mc-bridge-dsh
node verify.mjs      # 离线自检：不需要 DSH，直接打真实桥
```

用 `plugin_manager install_bundle <mc-bridge-dsh 路径>` 装进 DSH profile，重启 DSH 后
`mc_server` / `mc_watch` / `mc_bridge` 三个工具出现。

### 4. 让机器人连上来

任何客户端连 `127.0.0.1:25565`（offline 模式，用户名随意、无需登录），然后问服务端看到了什么：

```bash
curl "http://127.0.0.1:25963/players"
curl "http://127.0.0.1:25963/events?since=0&limit=50"
```

## 桥能回答什么

33 个端点，除三个注册表导出（TSV 文本，方便 diff 和流式处理）外都是 JSON。`GET /` 会自述全部端点。
完整参数与返回结构见 [`docs/endpoints.md`](docs/endpoints.md)。

| 需求 | 端点 |
| --- | --- |
| 服务器在不在、什么版本、有哪些世界 | `GET /status` `GET /worlds` `GET /world?name=` |
| 谁在线、精确坐标 | `GET /players` `GET /player?name=` `GET /inventory?name=` |
| 单个方块（含原版 id 与碰撞盒） | `GET /block?world=&x=&y=&z=` |
| 批量方块 / 整个区域 | `POST /blocks` `GET /region?…&dx=&dy=&dz=` |
| 一个 16³ section 的原始 state id（区块格式） | `GET /section?world=&x=&y=&z=` |
| 某点附近的实体 | `GET /entities?world=&x=&y=&z=&radius=` |
| 改世界（搭夹具） | `POST /setblock` `POST /fill` `GET /spawn` |
| 区块簿记（`setblock` 为什么没生效） | `GET /chunk` `GET /world/forceload` |
| 执行控制台命令**并拿到输出** | `POST /command {"command":"…"}` |
| 服务端真正发生了什么 | `GET /events?since=&type=&limit=` |
| 某玩家逐 tick 的移动轨迹 | `POST /trace/start?player=&limit=` `GET /trace/data` |
| 原版注册表真值 | `GET /registry/blocks` `GET /registry/items` `GET /registry/entities` |
| 服务端日志（JUL 环 + `logs/latest.log`） | `GET /log` `GET /log/file?tail=` |
| 等待 N tick | `POST /tick/wait?ticks=` |

所有游戏状态读取都切回主线程（`callSyncMethod`，15 秒超时），因此拿到的是**一致快照**，
而不是 HTTP 线程读到的一团撕裂状态。

### 事件日志

`/events` 返回 `{ok, since, next, count, entries}`，把 `next` 当作下次的 `since` 就能增量轮询。

```json
{"seq":42,"t":1760000000000,"tick":4801,"type":"break","player":"ProtoBot",
 "block":"minecraft:oak_log","dataBefore":"minecraft:oak_log[axis=y]"}
```

记录类型：`join quit swing interact interact_entity attack damage break place consume drop pickup chat
command death respawn container_open container_close click click_after held_change swap_hands
toggle_flight toggle_sneak toggle_sprint game_mode exp food effect book_edit sign_change trace_start
trace_stop`。其中 `click_after` 是 `click` 之后下一 tick 的快照 —— 容器转移类断言靠它才稳定。

## 两个插件

### `prototest-bridge/` —— Paper 26.3 服务端插件

Java 25、Paper API 26.3、**零第三方依赖**（HTTP 用 JDK 自带的 `com.sun.net.httpserver`，
JSON 是 300 行手写编解码）。单个 jar，默认只绑环回地址。

| 类 | 职责 |
| --- | --- |
| `PrototestBridge` | 插件生命周期、配置、`/prototest` 命令（status/url/log/events/trace/state/selfcheck） |
| `BridgeServer` | HTTP 路由、query/body 解析、切主线程、token 鉴权 |
| `BridgeState` | 权威投影：status、玩家、背包、方块（含 `stateId`/`collision`）、区域、section、注册表 |
| `EventRecorder` | 30 种事件、6000 条环、`seq` 游标、逐 tick 移动轨迹 |
| `LogCapture` | 服务端日志环（每秒重挂，因为 Paper 会重置 JUL 管理器） |
| `Json`、`Plain`、`BridgeCommandSender` | 零依赖 JSON、组件转文本、命令输出捕获 |

配置（`plugins/prototest-bridge/config.yml`）：

```yaml
host: "127.0.0.1"   # 默认环回；这个桥有完整的世界写权限
port: 25963
token: ""           # 非空时每个请求都要带 ?token= 或 X-Bridge-Token
debug: false        # 每个请求打一行日志
```

### `mc-bridge-dsh/` —— DSH 插件包

`@local/mc-bridge`，三个工具，零依赖（只用全局 `fetch`）：

| 工具 | 用途 |
| --- | --- |
| `mc_server` | 一次快照：版本、tick、TPS、在线玩家位置/血量/游戏模式、世界 |
| `mc_watch` | 带 `seq` 游标的事件日志，可选先推进 N tick |
| `mc_bridge` | 任意端点的原始透传 —— 另两个工具就是建在它上面的 |

为什么除了两个便利工具还要留原始透传：真正有意思的检查往往只涉及一个具体端点
（`/block` 看 state id、`/section` 验区块解码、`/trace/data` 验移动），
给每个端点造工具反而把 API 藏起来了。

配置在 `cordis.patch.yml` 的 bundle 行（`baseUrl`、`token`、`timeoutMs`）。

## 方块 state id 与 section 解码

`GET /registry/blocks` 返回 26.3 的全部 **35723** 个状态：

```
0	minecraft:air
1	minecraft:stone
2	minecraft:granite
3	minecraft:polished_granite
4	minecraft:diorite
5	minecraft:polished_diorite
6	minecraft:andesite
7	minecraft:polished_andesite
8	minecraft:grass_block[snowy=true]
9	minecraft:grass_block[snowy=false]
10	minecraft:dirt
…
#total	35723
```

属性的写法与线网归一化后的写法一致（全部属性、显式取值、按名排序），
因此这份文本可以直接和客户端自己的表逐行比对 —— 见
[`examples/compare_blocks.py`](examples/compare_blocks.py)，它会报出 id/名称/属性差异。

`GET /section?world=&x=&y=&z=`（`x,y,z` 为 section 最小角的**方块坐标**）返回一个 16³ section 的
4096 个 id，索引顺序 `(y*16 + z)*16 + x` —— 也就是区块 section 的调色板压缩长整型数组**解包后**的顺序。
不需要真客户端就能验区块解码。

## 一次完整的验证流程

`examples/` 里就是当初用来验证一个客户端的脚本（去掉了客户端本身）：

| 脚本 | 断言什么 |
| --- | --- |
| [`probe_bot.py`](examples/probe_bot.py) | 连真 bot，逐项检查进服、出生坐标、`send_look` → 服务端 yaw/pitch、快捷栏、命令、聊天、state id 解码、碰撞盒，并打印能力矩阵 |
| [`probe_shapes.py`](examples/probe_shapes.py) | 在服务端放 39 个代表性状态，读权威碰撞盒，与客户端模型对比 |
| [`compare_blocks.py`](examples/compare_blocks.py) | 全表 diff：`GET /registry/blocks` vs 客户端方块数据文件 |

一次检查长这样（摘自 `probe_shapes.py`），也正是这个桥存在的理由：

```python
POST /setblock  {"data": "minecraft:oak_stairs[facing=north]"}
GET  /block     → collision = [[0,0,0,1,0.5,1], [0,0.5,0,1,1,0.5]]   # 服务端真值
客户端模型       → [[0,0,0,1,1,1]]                                    # 错：整格
```

同样的思路，用裸 `curl`：

```bash
curl -s -X POST 'http://127.0.0.1:25963/setblock?world=world&x=5&y=-60&z=5' \
     -H 'content-type: application/json' -d '{"data":"minecraft:oak_stairs[facing=north]"}'
curl -s 'http://127.0.0.1:25963/block?world=world&x=5&y=-60&z=5'
curl -s -X POST 'http://127.0.0.1:25963/tick/wait?ticks=20'
curl -s 'http://127.0.0.1:25963/events?since=0&type=place'
```

## 仓库结构

```
mc-prototest-bridge/
├── prototest-bridge/           Paper 26.3 插件（Java 25，零依赖）
│   ├── src/main/java/com/prototest/bridge/
│   ├── src/main/resources/{plugin.yml,config.yml}
│   ├── build.ps1               ECJ 编译 + 打 jar + 可选部署（推荐路径）
│   ├── fetch-ecj.ps1           下载 build.ps1 用的 Eclipse 编译器
│   ├── restart-server.ps1      杀掉 → 等待 → 重启本地测试服
│   ├── build.gradle.kts        Gradle 路径（需要 -PserverDir=…）
│   └── README.md
├── mc-bridge-dsh/              DSH 插件包：mc_server / mc_watch / mc_bridge
│   ├── index.js                三个工具，零 import
│   ├── cordis.patch.yml        bundle patch（baseUrl/token/timeoutMs）
│   ├── verify.mjs              离线自检，11 项
│   ├── locale/、icon.svg
│   └── README.md
├── examples/                   验证脚本
└── docs/
    ├── endpoints.md            HTTP API 完整参考
    └── findings-26.3.md        这套设施在一个客户端 26.3 支持上发现的问题
```

## 构建

### `build.ps1`（推荐，已实测）

对**服务端自己的 jar** 编译（`versions/**/*.jar` + `libraries/**/*.jar`），
因为插件要读 `paper-api` 里没有的原版内部类（`Block.BLOCK_STATE_REGISTRY`、`BuiltInRegistries`、
`CraftBlockData`）。

```powershell
pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' [-Deploy]
```

它故意用 **ECJ**（Eclipse 编译器）而不是 `javac`：

* `javac` 25.0.3 在这套源码上会崩：`NullPointerException: Cannot read field "kind" because "tree.sym"
  is null`（`Flow$AssignAnalyzer.visitIdent`）—— 这是编译器 bug，不是源码错误。
* ECJ 3.42（缺了会由 `fetch-ecj.ps1` 自动下载）能编译同样的源码；它只需**读懂** Java 25 字节码
  （产出 Java 24 class 文件，Java 25 运行时照常加载）。

### Gradle（需要服务端 jar）

```powershell
./gradlew build -PserverDir='C:\path\to\paper-server'
```

`serverDir` 属性会把这些 jar 加进编译 classpath。不给的话会在 `net.minecraft.*` 导入处失败 ——
这些类在服务端里，不在 `paper-api` 里。这条路径留给 CI 风格的构建；实际验证过的是 `build.ps1`。

## 兼容性与限制

* **仅 Minecraft 26.3**：插件声明 `api-version: "26.3"` 且直接调用 Mojang 内部类。移植通常只需要改
  `paper-api` 版本并复核三处 NMS 调用（`BridgeState.blockStates`、`items`、`sectionIds`），
  其余都是 Bukkit API。
* **Java 25** 运行时（Paper 26.3 要求）。
* **默认只绑环回地址**。这个桥能以控制台身份改方块、跑命令 —— 不要对外暴露。确实要绑宽时请设 `token`。
* **只支持 Paper，不支持 Folia**（桥切到全局 region scheduler，`plugin.yml` 未声明 Folia 支持）。
* 注册表导出现在首次调用后缓存（`?fresh=1` 重建）；方块表运行时不可变，所以只有开发插件时才有用。
* 日志捕获是尽力而为（Paper 启动期会重置 JUL 管理器）；`/log/file` 直接读 `logs/latest.log`，是可靠兜底。

## 常见坑

| 现象 | 原因 / 处理 |
| --- | --- |
| `POST /setblock` 没效果，控制台 `setblock` 报 `argument.pos.unloaded` | 没有已加载区块（没玩家在线）。用插件的 `/setblock`、`/fill`，或 `GET /world/forceload?cx=&cz=&radius=1`。 |
| 桥返回 `{"ok":false,…}` 且 HTTP 500 | 主线程调用抛异常，消息就是异常文本；再看 `/log/file?tail=50`。 |
| 外面套的脚本报 `Cannot read properties of undefined (reading 'filter')` | 某个包装层在解析脚本的 JSON 输出；直接用 `/status` 核状态，脚本其实是成功的。 |
| Java 报 `PKIX path building failed` | JDK 信任库不信任你的 TLS 拦截代理。启动加 `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`（Windows）或导入代理 CA。 |
| bot「有时」能动（快捷栏、聊天、命令） | bot 被怪物打死了；或者在事件循环里用阻塞 HTTP 客户端调桥。见下面两条规则。 |
| Gradle 下载 wrapper 时 `PKIX path building failed` | 同一个 TLS 问题；改用 `build.ps1`。 |

两条款了真金白银学到的规则：

1. **不要在异步客户端的事件循环里用阻塞方式调桥。** 阻塞的 `urllib` 会饿死 socket writer，
   bot 停止 flush 数据包 —— 看起来完全像「服务端不理我的包」。请用 `asyncio.to_thread`。
2. **不要用控制台 `setblock`/`fill` 搭夹具。** 无玩家时 Paper 不保留区块，命令会以
   `argument.pos.unloaded` 失败；插件的 `/setblock` 会先加载区块，永远可用。
   （26.3 里 `setKeepSpawnInMemory` 和 `spawnChunkRadius` 已经不起作用，`/world/forceload` 才是有效手段。）

## 实测发现

[`docs/findings-26.3.md`](docs/findings-26.3.md) 是这套设施在某个客户端（`mc-proto-bot`）首次 26.3
实测时产出的报告：整张方块 state 调色板与服务端逐字节一致，但随包发布的碰撞数据是**占位表**
（所有状态都存成整格），且客户端所需的 14 项交互能力缺 7 项。

这就是这个桥的意义：「支持 26.3」这种说法，要么变成一个 diff、一个 JSON 字段、一行事件，
要么就只是一个说法。

## License

MIT —— 见 [LICENSE](LICENSE)。
