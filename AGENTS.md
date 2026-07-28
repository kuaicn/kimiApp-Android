# kimiApp（Android 客户端）开发指南

Kimi Code 的 Android 客户端，基于 `apps/kimi-web` 的 `/api/v1` 协议（REST + WebSocket）连接运行中的 kap-server（`kimi web` 服务端）。纯客户端，不内嵌引擎。UI 仅中文。

## 构建与测试

```bash
# 需要 JAVA_HOME 指向 JDK 17+（本机用 Android Studio 自带 JBR）
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:assembleDebug        # 编译调试 APK
./gradlew :app:testDebugUnitTest    # 单元测试（纯 JVM，JUnit4）
./gradlew :app:assembleRelease      # 正式包：keystore/ 下的签名密钥签名，不混淆
```

- 应用名称：**kimi code**；applicationId `com.kimi.app`。
- 正式签名：`keystore/kimi-release.jks` + `keystore/keystore.properties`（均 gitignore，不入库）；
  重新生成用 `keytool -genkeypair -alias kimi -keyalg RSA -validity 10950`。
  debug 与 release 签名不同，覆盖安装前需先卸载旧包。

- SDK 由 `local.properties` 的 `sdk.dir` 提供（当前 `~/Android/Sdk`，需 platform `android-37.0`）。
- 模拟器连接宿主机服务端：地址填 `http://10.0.2.2:58627`，Token 见 `kimi web` 启动输出。
- 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。

## 技术栈（第三方库优先，不造轮子）

- Kotlin 2.4.10 + Jetpack Compose（BOM 2026.06.01）+ Material 3。
- Retrofit 3 + OkHttp 5（REST）；OkHttp WebSocket（`/api/v1/ws`）。
- kotlinx.serialization（wire DTO，字段保持 wire 上的 snake_case）。
- `com.mikepenz:multiplatform-markdown-renderer-m3`（Markdown）、Coil 3（图片，带 Bearer 头）、DataStore Preferences（本地配置）、`ulid-creator`（X-Request-Id）。
- 无 DI 框架：`AppContainer` 手动装配单例；无导航库：状态驱动条件渲染 + `ModalNavigationDrawer`（与 kimi-web 无路由一致）。

## AGP 9 注意事项（容易踩坑）

- AGP 9.3 默认"内置 Kotlin"（2.2 编译器），读不了 Kotlin 2.4 构建的依赖（Coil/Markdown 库）。
  项目已在 `gradle.properties` 回退 `android.builtInKotlin=false` + `android.newDsl=false`（旧 DSL），
  因此 `build.gradle.kts` 用 `compileSdk = 37`、`isMinifyEnabled`，不要用 AGP 9 新 DSL 写法。
- compose/serialization 编译器插件版本必须与 Kotlin 插件一致（`libs.versions.toml` 的 `kotlin`）。

## 架构

```
com.kimi.app
├── KimiApp / MainActivity / AppContainer（手动 DI）
├── core/SettingsStore      DataStore：多服务器配置、token、clientId、主题、草稿、收藏模型
├── data/
│   ├── wire/Wire.kt        全部 wire DTO（与 apps/kimi-web/src/api/daemon/wire.ts 一一对应）
│   ├── wire/WsFrames.kt    WS 帧/事件载荷
│   ├── api/KimiApi.kt      Retrofit 接口（JSON 端点；二进制走 FileTransfer）
│   ├── api/ApiSupport.kt   信封解包、ApiException（401/40101 → serverAuthRequired）、OkHttp 拦截器
│   ├── api/FileTransfer.kt 上传 /files、导出 zip 到下载目录
│   ├── ws/KimiSocket.kt    WS 状态机：hello 握手、订阅、ping/pong、陈旧检测、退避重连、
│   │                       classifyFrame（协议 event.* vs 原始 agent-core 事件二段分类）
│   └── store/
│       ├── AppState.kt     不可变状态树（≈ kimi-web rawState）
│       ├── EventReducer.kt eventReducer.ts 移植
│       ├── AgentProjector.kt agentEventProjector.ts 主会话路径移植（volatile delta offset 对齐、
│       │                       流式消息播种、tool.call.started/result、retry 复用）
│       ├── TurnGrouper.kt  messagesToTurns.ts 移植（合并/边界/去重/审批块/附件恢复）
│       └── KimiClient.kt   总协调（≈ useKimiWebClient）：加载流水线、快照同步、全部写操作
└── ui/                     Compose 界面（全中文）
    ├── AppRoot.kt          根分派：连接页/加载/认证门/主界面 + 全局通知 + 服务器认证弹窗
    ├── connect/            服务器连接页（多配置切换）
    ├── drawer/             会话抽屉（工作区分组、徽章、归档区、搜索、添加工作区 fs:browse）
    ├── chat/               ChatScreen、Composer（附件/斜杠命令/排队）、blocks（Markdown/思考/
    │                       工具卡/审批卡/问题卡/Diff）、GoalStrip
    ├── panels/TasksSheet   后台任务面板
    ├── files/FileTreeDialog 文件树 / Git 变更 / 文件预览 / Diff
    ├── settings/           设置、模型选择器
    └── auth/               OAuth 设备码登录、服务器 Bearer 认证弹窗
```

## 协议要点（改动前必读，与 kimi-web 对齐）

- REST 统一信封 `{code,msg,data}`；`40101`/HTTP 401 → 全局服务器认证弹窗。
- **路径首段含冒号的端点（`fs:browse` / `fs:home` / `providers:refresh`）Retrofit 会把首段误判为
  URL scheme（"Malformed URL"），必须用 `@Url` 传完整 URL**（`apiBaseUrl + "fs:browse"`）。
- 会话创建：`metadata` 必须始终为对象（可空 `{}`）；**prompt 必须显式携带 model/thinking**
  （daemon 不做服务端默认模型回退，否则回合立即 `model.not_configured` 失败）。
- WS：kap-server 的原始 agent-core 事件也带 `event.` 前缀，必须按 `classifyFrame` 二段分类；
  流式文本走 **volatile** `assistant.delta`/`thinking.delta`（payload.delta 为字符串、信封带
  step 相对 offset），offset 断档（gap）必须全量重新快照；volatile 帧不推进订阅游标。
- 会话级 `error` 帧（有 session_id）是 agent 错误事件，不是连接错误。
- 快照流：`GET /sessions/{id}/snapshot` → 消息 + in_flight_turn 播种 → `subscribe(seq, epoch)`；
  `resync_required` / delta gap → 重新快照。

## 约定

- 协议字段/行为一律对照 `apps/kimi-web/src/api/daemon/`（wire.ts/client.ts/ws.ts/eventReducer.ts、
  agentEventProjector.ts）与 `composables/messagesToTurns.ts` 移植，不凭记忆编造。
- wire DTO 保留 snake_case 字段名（与 wire.ts 一致），应用层模型用 camelCase。
- 已排除：终端仿真、BTW 侧聊、swarm 群模式专属 UI。
- 单元测试集中在 `app/src/test/`（Reducer/Grouper 纯 JVM，不依赖 Android 框架）。
- 本项目不在 pnpm workspace / changeset 流程内，无需 changeset。
