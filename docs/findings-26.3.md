# mc-proto-bot × Paper 26.3 真值测试平台（首轮报告）

> **来源说明**：本文是这套工具第一次全量运行时写下的报告，路径沿用当时的工作区
> （`repo/` = mc-proto-bot 检出，`artifacts/` = 当次转储与草稿）。可复现的部分已抽到
> [`../examples/`](../examples/)（`compare_blocks.py` / `probe_shapes.py` / `probe_bot.py`），
> 其余是当时的现场记录。

目标：给 `repo/`（mc-proto-bot，Python 3.12+ 协议客户端）建一套**服务端权威真值**的验证环境，
让「库改得对不对」可以一键复测，而不是靠肉眼读代码。

结论先说：**环境已经跑通，并且立刻抓到一个真 bug** —— 26.3 的方块物理数据
（`repo/protobot/data/blocks-26.3.json.gz`）是**占位数据**：state id/名称 100% 正确，
但 `shapes` / `blocks_motion` / `climbable` / `bounce_restitution` 等物理语义全错，
导致碰撞预测对非整格方块（楼梯、半砖、栅栏、门、告示牌……）几乎全错。采样 39 个状态，
**只有 1 个（整格对照）一致**。

---

## 1. 架构

```
┌────────────────────┐  MC 协议 (25565, offline mode)  ┌──────────────────────────┐
│  repo/ (Python 库) │ ──────────────────────────────► │  Test26.3Server          │
│  Bot / World / …   │                                 │  Paper 26.3 build 205    │
└────────────────────┘                                 │  + prototest-bridge 插件  │
          ▲                                            └──────────┬───────────────┘
          │ 读库内部状态                                          │ HTTP/JSON (127.0.0.1:25963)
          │                                                       ▼
   artifacts/*.py（对照脚本）  ◄──── HTTP ────  服务端真值：方块 stateId / 碰撞盒 /
                                                玩家状态 / 实体 / 事件流 / 注册表
                                                        ▲
                                                        │ HTTP
                                              ┌─────────┴─────────┐
                                              │ DSH 插件 mc-bridge │  mc_server / mc_watch / mc_bridge
                                              └───────────────────┘
```

- **MC 侧插件** `prototest-bridge/`（Java 25，Paper 插件，零第三方依赖，JDK 自带 `com.sun.net.httpserver`）
- **DSH 侧插件** `mc-bridge-dsh/`（`@local/mc-bridge`，把桥暴露成 agent 工具）
- **对照脚本** `artifacts/*.py`（用 `repo/.venv` 的 Python 3.12 跑）

## 2. 组件与启动

### 2.1 Paper 服务器（`Test26.3Server/`）

已经配置好并可直接复用：

- `server.properties`：`online-mode=false`、`enforce-secure-profile=false`、`allow-flight=true`、
  `spawn-protection=0`、`difficulty=peaceful`、`spawn-monsters=false`、`level-type=minecraft:flat`、
  `server-port=25565`、`op-permission-level=4`
- 首次启动的原版 jar 已缓存：`cache/mojang_26.3.jar`（62,294,556 B，sha256 `d052f14d…be64`）
- 启动：`Test26.3Server/start-server.bat`（内含 `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`，
  本机 Java 不信任拦截代理 CA，必须带这个参数，否则 paperclip 下载原版 jar 会 PKIX 失败）

> `difficulty=peaceful` 是**必须**的：之前 slime 在超平坦世界里把 bot 打死，服务端会对死尸忽略
> 后续动作，导致大量「假阴性」。

### 2.2 MC 侧插件 `prototest-bridge`

- 源码 `prototest-bridge/src/main/java/com/prototest/bridge/`：
  `PrototestBridge`（主类）/ `BridgeServer`（HTTP 路由）/ `BridgeState`（权威状态投影）/
  `EventRecorder`（事件真值 + 移动轨迹）/ `LogCapture`（服务端日志环）/ `Json`、`Plain`（零依赖 JSON / 文本）
- 监听 `127.0.0.1:25963`（`config.yml`），只绑本机，默认不鉴权（可配 `token`）
- 所有 Bukkit API 调用都切回主线程（`callSyncMethod` + 15s 超时）
- 构建：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File prototest-bridge\build.ps1 -Deploy`
  （用 ECJ 编译，见 §6；产物自动拷到 `Test26.3Server/plugins/`）
- 重启：`prototest-bridge\restart-server.ps1`

主要端点（完整列表见 `GET /`）：

| 类别 | 端点 |
| --- | --- |
| 服务端 | `/status` `/players` `/player?name=` `/inventory?name=` `/worlds` `/world?name=` |
| 世界真值 | `/block?world&x&y&z`（含 `stateId`、`collision`、`boundingBox`、`passable`）`/region` `/section` `/blocks`(POST) |
| 写世界 | `/setblock`(POST) `/fill`(POST) `/chunk` `/world/forceload` `/world/keepspawn` `/spawn` |
| 注册表 | `/registry/blocks`（35723 行 `id<TAB>minecraft:x[props]`）`/registry/items` `/registry/entities` `/registry/item` `/registry/block` |
| 事件真值 | `/events?since&limit&type` `/events/clear` `/trace/start` `/trace/stop` `/trace/data` |
| 运维 | `/command`(POST，带 `output`/`log`) `/log` `/log/file` `/op`(POST) `/tick/wait` |

事件类型（`EventRecorder`）：`join quit swing interact interact_entity attack damage break place consume drop
pickup chat command death respawn container_open container_close click click_after held_change swap_hands
toggle_flight toggle_sneak toggle_sprint game_mode exp food effect book_edit sign_change trace_start trace_stop`。

### 2.3 DSH 侧插件 `mc-bridge-dsh`

- `@local/mc-bridge`，已 `plugin_manager install_bundle` 安装成功（`stage=enable, enabled=true`）
- 三个工具：`mc_server`（状态+玩家）、`mc_watch`（`since` 游标读事件，可选 `waitTicks` 先等若干 tick）、
  `mc_bridge`（任意端点透传，`path/method/query/body`）
- **注意**：安装返回 `application="restart-required"`，`mc_*` 工具要**重启 DSH** 后才会出现在本会话
- 离线自检：`cd mc-bridge-dsh; node verify.mjs`（11 项全过，不依赖 DSH 运行时）

## 3. 三组权威校验（现在都能一键跑）

```powershell
# 全部跑一遍（需要服务器在跑）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File artifacts\run_all.ps1
```

| 脚本 | 校验什么 | 当前结果 |
| --- | --- | --- |
| `artifacts/compare_blocks.py` | 服务端 35723 个全局 block state 与 `blocks-26.3.json.gz` 的 id↔名称全表对比 | **identical**（0 差异） |
| `artifacts/probe_bot.py` | 真 bot 连服，逐动作读服务端真值（进服/坐标/朝向/快捷栏/命令/聊天/方块/碰撞/能力矩阵） | 12 PASS / 1 FAIL（楼梯碰撞，见 §4） |
| `artifacts/probe_shapes.py` | 39 个代表性方块状态：服务端 `/setblock` 写、`/block` 读碰撞盒，对比库的 `block_collision_boxes` | **1/39 一致** |

`probe_bot.py` 的检查项（每条都是「库以为的」vs「服务端看到的」）：

- bridge 报告的是 Paper 26.3、调色板 35723 条
- 服务端能看到 bot 进服、进服事件带坐标与游戏模式、双方出生坐标一致
- `send_look` → 服务端 yaw/pitch 生效；`select_hotbar_slot(3)` → 服务端 `heldSlot=3`
- `send_command` → 服务端 `PlayerCommandPreprocessEvent`；`send_message` → `AsyncChatEvent`
- 服务端 `/setblock` 放石头 → 客户端解码出 `stateId=1`；周围 12 个采样点 stateId 全一致
- 石头碰撞盒一致；楼梯碰撞盒（预期失败，§4）
- 能力矩阵：14 项里 7 项缺失（§5）

## 4. 关键发现 A：26.3 方块物理数据是占位数据

`repo/protobot/data/blocks-26.3.json.gz` 共 35723 个状态，**id 与名称与服务器完全一致**
（`compare_blocks.py` 无差异，所以 chunk 解码是对的），但物理字段是默认值：

```
文件                          states   shapes 整格率   custom  shapes   empty
blocks-1.21.11.json.gz         29671        10.3%          21192          5417
blocks-26.1.json.gz            29873        10.9%          21193          5418
blocks-26.2.json.gz            32366        10.2%          23649          5430
blocks-26.3.json.gz            35723       100.0%              0             4   ← 占位
```

其它物理字段同样整片默认（对照 26.2）：

| 字段 | 26.2 | 26.3 |
| --- | --- | --- |
| `blocks_motion=true` | 27816 / 32366 | **35719 / 35723**（连空气也 true） |
| `fluid_sturdy_faces` 非空 | 8868 | **35719** |
| `climbable=true` | 180 | **72**（梯子/藤蔓等缺失） |
| `bounce_restitution` 非零 | 257 | **1**（史莱姆/床缺失） |
| `shapes` 自定义形状 | 23649 | **0** |

有 535 个「明显不是整格」的方块名（stairs / slab / fence / button / door / pane / sign / chest /
carpet / snow / anvil / cauldron / wall / rail / lantern …）在 26.3 数据里全部被存成整格。

服务端逐状态实测（`artifacts/probe_shapes.py`，把方块实际放进世界再用 `/block` 读权威碰撞盒）：

```
oak_stairs[facing=north]   server=[[0,0,0,1,0.5,1],[0,0.5,0,1,1,0.5]]   client=[[0,0,0,1,1,1]]
oak_slab[type=bottom]      server=[[0,0,0,1,0.5,1]]                       client=[[0,0,0,1,1,1]]
oak_fence                  server=[[0.375,0,0.375,0.625,1.5,0.625]]       client=[[0,0,0,1,1,1]]
enchanting_table           server=[[0,0,0,1,0.75,1]]                      client=[[0,0,0,1,1,1]]
short_grass / rail / sign / water   server=[]（无碰撞）                    client=[[0,0,0,1,1,1]]
```

**影响**：`protobot/physics/engine.py` 那套逐行对齐 `LivingEntity.travel` 的搬运逻辑，
在 26.3 上跑的是错误的几何体 —— 上不了楼梯、进不了半砖上方、被告示牌/铁轨/水挡住、
栅栏可以穿过、梯子和史莱姆块不会生效。这不是引擎的问题，是喂给它的数据没生成。

**建议修复**：重新生成 `blocks-26.3.json.gz`（用与 26.2 相同的导出流程；若来自 Mojang
`reports/blocks.json`，需要 26.3 的数据生成器输出），生成后本套脚本可直接回归：
`compare_blocks.py` 验 id/名称、`probe_shapes.py` 验碰撞盒。

## 5. 关键发现 B：P0 交互缺口实测确认

`probe_bot.py` 的能力矩阵（`hasattr` 扫描 + 官方 API 名）：

- 缺失（7/14）：挥臂、攻击实体、交互实体、对方块使用物品（放方块 / 右键开容器）、
  破坏方块、停止破坏、射线/视线目标
- 存在：`use_item`、`drop_item`/`drop_inventory_item`、`select_hotbar_slot`、
  `click_container`、`send_command`、`send_message`、`respawn`

即**用真实服务器复现了评估报告里的 P0 结论**：库能连、能动、能聊天、能点容器，
但无法挖/放/攻击/交互，因此也无法自己开箱子或工作台（`ServerboundUseItemOn` / `PlayerAction` /
`Interact` / `Swing` 四个包 + raycast 全缺）。

## 6. 踩过的坑（写在这里省下一次重走）

1. **Java 编译**：本机只有 JDK 25，但 `javac` 25.0.3 在本源集上会崩
   （`NullPointerException … Flow$AssignAnalyzer.visitIdent(Flow.java:3189)`），
   `-XDcompilePolicy=simple` 只能偶尔绕过。**解决办法：改用 ECJ**
   （`prototest-bridge/libs/ecj-3.42.0.jar`，`java -jar ecj-3.42.0.jar -24 …`），
   `build.ps1` 已封装；Gradle 则因 wrapper 下载走 TLS 直接 PKIX 失败而放弃。
2. **Java 的 TLS**：所有 `java` 命令都要 `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`；
   PowerShell 里必须写成单引号字符串 `'-Dfoo=bar'`，否则被拆成多个参数。
3. **控制台命令看不到方块**：无玩家时 Paper 不保留区块，`/setblock` 报 `argument.pos.unloaded`。
   要么用桥的 `/setblock`、`/fill`（内部先 `loadChunkAt`），要么 `/world/forceload` 强制加载。
   `setKeepSpawnInMemory(true)` 与 `spawnChunkRadius` 在 26.3 都已无效。
4. **Python 探针**：在 asyncio 事件循环里用阻塞的 `urllib` 调桥，会饿死 socket 的 flush ——
   表现为「look 之后所有包都不生效、聊天要等断开才出现」。`probe_bot.py` 已全部改成
   `asyncio.to_thread`；写测试时务必遵守。
5. `quit` 事件里 `reason=ERRONEOUS_STATE` 是**正常断开**（客户端不发断开原因包），不是错误。
6. 脚本用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File …` 跑（默认策略禁止 .ps1）；
   写 .java 文件用 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` 避免 BOM。

## 7. 下一步建议（按性价比排序）

1. **重新生成 26.3 方块数据**（§4）—— 现成的回归脚本已就位，这是投入产出最高的一步。
2. **补 P0 交互三件套**：版本表加 4 个 serverbound id + raycast + `break/place/attack/interact/swing`，
   补完后 `probe_bot.py` 可以直接把「挖掉方块 → 服务端 `break` 事件 → 世界 stateId 变空气」
   「放下方块 → `place` 事件 → stateId 变成放置物」加成新检查项。
3. 把 `probe_bot.py` / `probe_shapes.py` 接进 `tests/`（需要本地服时 skip），或作为 CI 的手动 job。
4. 给 `mc-bridge` 加一个 `mc_assert` 便捷工具（把「期望 vs 服务端真值」写成一行断言）。
