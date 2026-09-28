<p align="center">
  <h1 align="center">MAA — Minecraft 整合包智能构筑系统</h1>
  <p align="center">
    <strong>M</strong>inecraft <strong>A</strong>I <strong>A</strong>gent
  </p>
  <p align="center">
    Java 21 · Spring Boot 4 · LangChain4j · Vue 3（无构建工具）
  </p>
</p>

---

**用一句自然语言描述你想要的整合包，MAA 负责搜索、筛选、依赖穿透与打包，产出一个可以直接导入启动器的 `.mrpack` 文件。**

```
你：我想玩一个60个模组的Neoforge1.21.1整合包，以铁魔法为核心，
    包含alex生物和alex洞穴，多加一些铁魔法的扩展，以冒险为主题
MAA：（约 12 秒后）75 个模组的清单 + 依赖关系图 + 可直接导入的 .mrpack
```

---

## 目录

- [这是什么](#这是什么)
- [一次真实的构筑](#一次真实的构筑)
- [它是怎么工作的](#它是怎么工作的)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [使用指南](#使用指南)
- [配置项](#配置项)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [开发](#开发)
- [安全设计](#安全设计)
- [常见问题](#常见问题)
- [许可与声明](#许可与声明)

---

## 这是什么

MAA 把「构筑一个整合包」拆成两半：

- **需要理解力的部分交给大模型**：读懂你要什么、把"铁魔法"对应到真实模组、从候选里挑出搭配得上的组合；
- **需要准确性的部分交给普通 Java 代码**：并发查 Modrinth、算重合度、递归解析依赖、查本地知识库规则、生成 `.mrpack`。

两个 LLM Agent（规划、审核）与三段确定性 Java 处理串成一条管线，各阶段的输入输出都是结构化数据（XML / JSON），所以每一步都能单独观察、单独测试。

数据来源只有 **Modrinth**。它不写代码、不训练模型，做的是"把几十上百个模组的检索与依赖整理工作压缩到一轮对话"。

**适合**

- 想快速把一个主题整合包（几十到两百个模组）凑齐、并看清依赖关系的玩家
- 想在现有整合包上做增删改、想知道"删了这个会不会断链"的玩家
- 想看"LLM 负责规划/审核 + 确定性代码负责检索/校验"这类多智能体工程怎么落地的开发者

**不适合**

- 要求"装上就能进游戏、绝不崩"的场景：依赖信息来自 Modrinth 上作者填写的元数据，作者改动就会改变结果（见[局限](#局限与诚实说明)）
- 上千模组、逐项精调的大型整合包：目标规模是几十到两百个模组

---

## 一次真实的构筑

下面这轮是 2026-09-27 的实测记录（日志原文），从提交到出结果约 **12 秒**：

```
【输入】
我想玩一个60个模组的Neoforge1.21.1整合包，以铁魔法为核心，包含alex生物和alex洞穴，
多加一些铁魔法的扩展，以冒险为主题

【规划师 2.9s】
  · 把中文黑话翻成真实 slug：「铁魔法」→ irons-spells-n-spellbooks（走本地别名词典，不靠模型记忆）
  · 调工具确认环境与目标：setTargetCount(60)、setPackName(铁魔法冒险纪)、setEnvironment(1.21.1/neoforge)
  · 产出搜索矩阵：adventure 35 · equipment 20 · mobs 15 · worldgen 12 · magic 10 · library 8
  · 把 irons-spells-n-spellbooks 标为 expand_addons（让 Java 去抓它的真实附属，而不是让模型编名字）

【召回（纯 Java）】
  · 本地版本预过滤：110 → 99 个候选（省下 11 次上游查询）
  · 委派结算：10 轮共下放 183 项，浏览器直连 Modrinth 完成 182 项，服务器真实出网 0 次

【审核员 1.4s】
  · 从候选池里精挑 37 个（本轮额度 = 目标 60 − 已在包内 12，按膨胀系数折成根模组预算）

【依赖穿透（纯 Java）】
  · 补入 26 个必需前置：citadel → alexs-mobs、lionfish-api → l_enders-cataclysm、curios、geckolib …
  · 环境自适应：alexs-mobs 未声明支持 1.21.1 → 自动换成有 1.21.1 版本的构建
  · 解决不了就如实说：alexscaves 在 Modrinth 上没有适配 1.21.1 的版本，也找不到合理平替 → 剔除并告知

【结果】
  · 最终 75 个模组（目标 60，依赖带出 15 个）
  · 包内类别构成（一个模组可属于多个类别）：adventure 29 · library 25 · magic 22 ·
    equipment 19 · utility 18 · mobs 14 · game-mechanics 12 · worldgen 9 …
```

注意最后两条：**该说不的地方它会说**——模组被换掉/剔除、哪一类没凑够，都会写进回复，而不是假装一切按计划完成。

---

## 它是怎么工作的

```
用户输入: "我想玩一个60个模组的Neoforge1.21.1整合包，以铁魔法为核心，……以冒险为主题"
         │
         ▼
┌──────────────────────────────────────────────────────────┐
│ 1. Architect Agent（LLM）                                │
│    理解需求 → 提取核心模组 → 生成 XML 蓝图               │
│    产出：core_mods / expand_addons / search_intents      │
└─────────────────────────┬────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────┐
│ 2. Java Retriever（纯 Java）                             │
│    虚拟线程并发多路召回 → 重合度打分 → 候选池 Top-N      │
└─────────────────────────┬────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────┐
│ 3. Critic Agent（LLM）                                   │
│    阅读候选池（标题/简介/下载量/得分）→ 剔除冲突、精选   │
│    产出：approved_mods                                   │
└─────────────────────────┬────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────┐
│ 4. Dependency Engine（纯 Java）                          │
│    BFS 依赖穿透 → 本地知识库冲突检测 → 非官方版本抢救    │
│    → Caffeine 缓存 + 全局令牌闸保护 Modrinth 调用        │
└──────────────────────────────────────────────────────────┘
```

**规划师与审核员严格隔离**：两个 LLM Agent 各自持有独立的 System Prompt，不共享上下文 —— 规划阶段不被审核阶段的取舍影响，反之亦然。这是刻意的设计约束，改提示词时不要把它们合并。

### 三个值得一提的机制

**① 取数委派** —— 查模组、查版本、查前置依赖这些请求，默认**下放给浏览器直连 Modrinth**，服务器只做校验。上例里 183 项取数全部由浏览器完成，服务器出网 0 次；浏览器没赶上时（网络慢、页面被关）才回落到服务器自抓。注册前会弹一次知情同意，说明这部分流量由用户自己的网络承担。

**② 全局令牌闸** —— 服务器自己出网时统一过一个 GCRA 令牌桶（默认 240 次/分钟、突发 40），排不到队就明确降级为"该项无法确定"，绝不硬闯限流。所有 Modrinth 出网都必须走它。

**③ 同一账号同时只跑一轮** —— 一轮构筑要跑几十秒到几分钟，期间候选池、勾选、导出快照都按"轮"算。服务端按 **会话 + 账号** 双重加锁：多开标签页、换浏览器、刷新页面都拦得住；异常中断有 TTL 兜底，不会把人永久卡住。

### 局限与诚实说明

- **依赖正确性取决于 Modrinth 元数据**：作者把前置从"声明依赖"改成 JarJar 内嵌、或写错依赖，闭包就会变。本地知识库（`maa_db/rules.json`）是补丁，不是万能药。
- **版本选择可能命中测试版**：取"最新可用版本"时不区分 release / beta，偶尔会选中作者刚发布的 beta。
- **可行性校验不在这条管线里**：早期版本会在服务器上真开一个 MC 服务端做崩溃自愈，已于 2026-09 下线（要维护服务器底座，而且服务端测不出客户端侧问题）。现在由配套工具 [MAA-Checker](https://github.com/WaitMyDawn/MAA-Checker/releases) 在**本机**启动客户端实例验证，结论直接对应"这台机器能不能进主菜单"。

---

## 核心特性

| 特性 | 说明 |
|---|---|
| **自然语言构筑** | 描述主题、体量、玩法，不用先想清楚要装哪些模组 |
| **中文黑话词典** | 「铁魔法」「灾变」「地牢浮现之时」等说法直接映射到真实 slug，不靠模型记忆猜 |
| **四阶段管线** | 规划 → 召回 → 审核 → 依赖穿透，阶段输入输出都是结构化数据，可单独观察 |
| **依赖自动补齐** | BFS 递归解析前置依赖，并处理"被依赖方已删除"的断链情况 |
| **冲突剔除** | 结合本地知识库规则（管理员规则 / Modrinth 同步 / 用户反馈）剔除已知恶性冲突 |
| **环境自适应** | 点名的模组不支持当前版本时，先找适配该版本的同名构建，再找平替，都不行就剔除并告知 |
| **依赖图谱可视化** | Vis.js 渲染 DAG，可直接从图上删模组、看"谁依赖它" |
| **候选池抽卡** | 服务器召回的候选模组以卡片分页展示（每页 15 个），本地实时搜索、勾选加入 |
| **偏好学习** | 每次构筑后更新类别/模组偏好权重（影响力度 0~1 可调），影响后续检索方向 |
| **模组黑名单** | 排除某模组时级联处理只依赖它的模组，避免留下残缺依赖 |
| **多用户账号** | 邮箱 + 验证码注册；账号或邮箱登录；找回密码；绑定/换绑/解绑邮箱 |
| **自备 LLM Key** | 每个用户在设置页填**自己的** DeepSeek Key；服务端 AES-256 加密存储，日志与响应里不出现明文 |
| **对话历史** | 对话与每轮结果持久化，可回看、可继续 |
| **一键导出** | 生成标准 `.mrpack`，导入 Prism / MultiMC / HMCL 等启动器即可 |
| **运行检验** | 生成后用 [MAA-Checker](https://github.com/WaitMyDawn/MAA-Checker/releases) 在本机真跑一遍再玩 |

---

## 快速开始

**环境要求**：JDK 21+、Maven（或用仓库自带的 `mvnw`）。不需要预装任何 Minecraft 服务端。

```bash
git clone https://github.com/WaitMyDawn/minecraft-ai-agent.git
cd minecraft-ai-agent

cp .env.example .env
# 编辑 .env：至少填 MAA_ENCRYPTION_SECRET（openssl rand -base64 32）

./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```

浏览器打开 <http://localhost:8080>。注册开关默认关闭，本地自用把它打开：

```bash
MAA_ALLOW_REGISTRATION=true
```

### 用 Docker 跑

仓库自带 `Dockerfile` 与 `docker-compose.yml`（JAR 通过卷挂载，更新代码不必重建镜像）：

```bash
docker compose build      # 只在改过 Dockerfile 时需要
docker compose up -d
docker compose ps         # STATUS 应出现 (healthy)
```

数据落在 `./maa_db/`（H2 文件库 + 规则文件），日志落在 `./maa_logs/`。

### LLM Key 从哪来

服务端**不需要**配置 LLM Key：每个用户登录后在「⚙️ 设置 → DeepSeek API Key」填自己的 Key（`sk-...`），服务端加密后存储。

管理员也可以设 `DEEPSEEK_API_KEY` 提供一个系统默认 Key，作为"用户还没填 Key 时"的兜底；不设的话，未配 Key 的用户会被告知先去设置页填。`ai.api.url` 指向 OpenAI 兼容端点，换模型只改这一行。

---

## 使用指南

### 1. 注册与登录

- 注册需要**邮箱 + 邮箱验证码**；邮箱是账号的唯一标识，也是自助找回密码的唯一途径
- 登录支持**账号号**（形如 `1005`）或**邮箱**
- 密码规则：**8~64 位，必须同时包含字母和数字**

### 2. 填自己的 LLM Key

右上角「⚙️ 设置 → DeepSeek API Key」。Key 只在提交时经过浏览器，服务端只保存密文。

### 3. 开始构筑

描述得越具体越准（环境、主题、体量、核心模组都可以写）：

> 我想玩一个60个模组的Neoforge1.21.1整合包，以铁魔法为核心，包含alex生物和alex洞穴，多加一些铁魔法的扩展，以冒险为主题

一轮会依次走四个阶段，界面上显示当前阶段与耗时；中途可以点「终止思考」。

### 4. 看依赖图谱 / 删模组

构筑完成后可切到图谱视图：节点是模组、边是"谁依赖谁"；右键能打开 Modrinth 页面、定位依赖链。进入删除模式后勾选删除，界面会提示"哪些模组会因为这次删除而断链"，并给出一键级联删除。

### 5. 抽卡选模组

「建议面板」把候选模组做成卡片（每页 15 个）：本地实时搜索、按类别与下载量筛选、按下载量/更新时间排序，勾选后批量加入当前整合包。

### 6. 导出整合包

点「生成 .mrpack」。导出前建议先跑一次 [MAA-Checker](https://github.com/WaitMyDawn/MAA-Checker/releases) —— 它会在本机真开一个客户端实例，告诉你这套组合能不能进主菜单。

### 7. 偏好与黑名单

- **偏好**：类别偏好（排序）+ 模组偏好（构建次数），配合「偏好影响权重」控制影响力度
- **黑名单**：永不参与构筑；排除时级联处理只依赖它的模组
- 两者都能导出/导入 JSON，方便换机器或备份

### 8. 邮箱与密码

- 「⚙️ 设置 → 📧 邮箱」可**绑定 / 换绑 / 解绑**邮箱
- **绑了邮箱**：改密码只需邮箱验证码（不需要当前密码）；忘记密码可自助重置
- **没绑邮箱**：改密码需要当前密码；忘记密码只能人工处理

> ⚠️ 解绑邮箱等于拆掉账号的找回通道，忘记密码后将无法自助找回。

---

## 配置项

所有配置都能写在项目根目录的 `.env`（程序启动时自动加载），或用同名环境变量注入 —— **环境变量优先级更高**。完整模板见 [.env.example](.env.example)。

### 必需

| 变量 | 默认 | 说明 |
|---|---|---|
| `MAA_ENCRYPTION_SECRET` | 空 | 加密用户 LLM Key 的密钥种子（`openssl rand -base64 32`）。**丢了 = 所有用户的 Key 解不开，必须让用户重填**，请离线备份 |
| `MAA_ALLOW_REGISTRATION` | `false` | 是否开放注册。需要注册时打开 |
| `DEEPSEEK_API_KEY` | 空 | 系统默认 LLM Key（可选兜底）。留空时用户必须自备 |

### 账号与邮件

| 变量 | 默认 | 说明 |
|---|---|---|
| `MAA_SESSION_TTL_DAYS` | `30` | 登录令牌的空闲有效期（滑动续期：一直在用就不会被踢下线） |
| `MAA_MAIL_ENABLED` | `false` | 邮件总开关。关闭时注册/找回密码会明确提示"邮件服务未启用" |
| `MAA_MAIL_PROVIDER` | `resend` | 生效通道：`resend` / `brevo` / `smtp` |
| `MAA_RESEND_API_KEY` / `MAA_RESEND_FROM` | 空 | Resend 通道（发件人必须是已验证域名下的地址） |
| `MAA_BREVO_API_KEY` / `MAA_BREVO_FROM` | 空 | Brevo 通道（额度可查，注册需手机核验） |
| `MAA_MAIL_HOST` / `PORT` / `USERNAME` / `PASSWORD` / `FROM` | 空 / `465` | SMTP 通道（阿里云 DirectMail、QQ、163 均可）。云厂商封 25 端口，用 465(SSL) |
| `MAA_EMAIL_CODE_MAX_PER_DAY` | `3` | 同一邮箱每天最多收到几个验证码（注册/改密码/换绑共用额度） |
| `MAA_EMAIL_CODE_COOLDOWN_MS` | `60000` | 同一邮箱两次发送的最小间隔 |

### 检索与限流

| 变量 | 默认 | 说明 |
|---|---|---|
| `MAA_MODRINTH_RATE_PER_MINUTE` | `240` | 服务器出网的全局令牌闸速率（Modrinth 实测限额约 300 次/分钟/IP，默认留了余量） |
| `MAA_MODRINTH_BURST` | `40` | 突发信用：允许一次连发多少请求 |
| `MAA_MODRINTH_MAX_WAIT_MS` | `30000` | 排队上限，超了就按"无法确定"降级 |
| `MAA_DELEGATION_ENABLED` | `true` | 是否把取数下放给浏览器直连 Modrinth |
| `MAA_DELEGATION_PER_CALL_BUDGET_MS` | `30000` | 单批委派最多等多久，超时由服务器兜底 |

### 运维

| 变量 | 默认 | 说明 |
|---|---|---|
| `MAA_OPS_TOKEN` | 空 | 运维端点口令。**空 = 端点当作不存在（404）**；在线备份脚本需要它 |
| `MAA_OPS_BACKUP_DIR` | `maa_db/backups` | 在线备份落盘目录 |
| `LOG_DIR` / `LOG_RETENTION_DAYS` | `./logs` / `14` | 应用日志目录与保留天数 |
| `MAA_DB_HEALTH_TTL_MS` | `60000` | `/api/health` 里数据库探针结果的缓存时长 |
| `MAA_MAIL_SIMULATE_QUOTA` | `false` | **验收专用**：打开后每次发信都按"额度用完"失败（不真发信），用来验证提示文案 |

### 存储

| 项 | 默认 | 说明 |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./maa_db/knowledge;AUTO_SERVER=TRUE` | 主库（用户、对话、偏好、规则） |
| `maa_db/modrinth_cache.mv.db` | — | Modrinth 元数据缓存（几十 MB，丢了会重新抓） |
| `maa_db/rules.json` / `aliases.json` / `loader-versions.json` | — | 规则、别名词典、加载器版本表 |

---

## 项目结构

```
Minecraft-AI-Agent/
├── src/main/java/yagen/waitmydawn/maa/
│   ├── MinecraftAiAgentApplication.java    # 入口：加载 .env → 安装日志 tee → 启动 Spring
│   ├── config/
│   │   ├── AppConfig.java                  # Caffeine 缓存 + 全局 RestClient（8s/15s 超时）
│   │   └── MainDataSourceConfig.java       # 主数据源 + JPA（连接池参数在这里设）
│   ├── controller/                         # REST 入口
│   │   ├── ChatController.java             # 四阶段管线编排（核心，也最大）
│   │   ├── ModpackController.java          # 预览 / 依赖解析 / .mrpack 构建 / 建议引擎
│   │   ├── UserController.java             # 注册登录、邮箱验证码、改密码、会话、AES 加解密
│   │   ├── PreferencesController.java      # 偏好 / 黑名单 / 对话历史 / 导出导入
│   │   ├── KnowledgeController.java        # 知识库查询
│   │   ├── KnowledgeFeedbackController.java# 用户反馈规则
│   │   ├── MetaController.java             # 类别清单等元数据
│   │   ├── HealthController.java           # /api/health（存活 / 就绪两种语义）
│   │   └── OpsController.java              # 运维：在线备份、发信自检、邮件通道状态
│   ├── service/
│   │   ├── AiAgentService.java             # Architect / Critic 两个 LLM Agent
│   │   ├── ArchitectPackTools.java         # 给规划师调用的工具（目标数/包名/环境/类别配额/移除模组）
│   │   ├── DependencyEngine.java           # BFS 依赖穿透 + 解析结果缓存
│   │   ├── ModrinthApiClient.java          # Modrinth API（指数退避）
│   │   ├── ModrinthFetcher.java            # 取数入口：先委派给浏览器，超时回源
│   │   ├── ModrinthThrottle.java           # 全局 GCRA 令牌闸
│   │   ├── DelegationTasks.java            # 委派任务表（Caffeine + 挂起的虚拟线程）
│   │   ├── KnowledgeDb.java                # 规则加载与查询（启动时同步 rules.json）
│   │   ├── ModAliasRegistry.java           # 中文黑话 → slug 词典
│   │   ├── LoaderVersionService.java       # 加载器版本表（定时刷新）
│   │   ├── PasswordHasher.java             # PBKDF2-HMAC-SHA256 + 每用户随机盐
│   │   ├── PasswordPolicy.java             # 密码规则（8~64 位 + 字母数字）
│   │   ├── EmailCodeService.java           # 验证码签发/校验/限流
│   │   ├── MailService.java                # 邮件通道路由 + 额度检查
│   │   ├── ResendMailClient.java           # Resend 通道
│   │   ├── BrevoMailClient.java            # Brevo 通道（额度可查）
│   │   ├── BackupService.java              # H2 在线备份（BACKUP TO）
│   │   ├── DbHealthProbe.java              # 数据库探针（带缓存）
│   │   ├── PackManifestService.java        # 导出快照（预览与导出必须是同一份）
│   │   └── MrpackParser.java               # .mrpack 解析
│   ├── model/                              # JPA 实体 + Repository
│   │   ├── User.java / UserRepository.java # 账号（邮箱唯一约束）
│   │   ├── Conversation / ChatMessage      # 对话历史
│   │   ├── CategoryPreference / ModPreference / ModBlacklist
│   │   └── KnowledgeRule.java              # ADMIN / MODRINTH / USER_FEEDBACK
│   ├── cache/                              # Modrinth 元数据缓存（H2 + 内存索引）
│   └── runtime/                            # 请求作用域与并发守卫
│       ├── RequestScope.java / RequestScopeFilter.java
│       ├── RoundGuard.java                 # 同会话/同账号同时只跑一轮
│       ├── SessionRegistry.java            # 登录令牌 + 空闲 TTL
│       └── DelegationContext.java          # 单轮委派状态
├── src/main/resources/
│   ├── application.properties              # 配置项与默认值（注释里写了"为什么这么设"）
│   └── static/
│       ├── index.html                      # 单文件 SPA 外壳（模板 + 装配）
│       └── js/                             # 原生 ES Modules
│           ├── store.js / api.js / modrinth-client.js
│           ├── features/                   # auth / chat / graph / gacha / prefs / pack-view …
│           ├── components/TreeNode.js
│           └── utils/                      # format / code-cooldown
├── ops/backup.sh                           # 备份脚本（在线 / 冷备 + 保留策略）
├── eval/                                   # 评测与门禁脚本
│   ├── frontend-structure-check.mjs        # 前端结构门
│   ├── fe-import-lines.mjs                 # import 行核对
│   ├── fe-delegation-check.mjs             # 委派取数逻辑
│   ├── golden-set.json / run-eval.ps1      # 黄金集端到端评测
│   ├── delegation-stats.mjs                # 委派效果统计
│   └── loader-versions-audit.mjs           # 加载器版本表缺口审计
├── Dockerfile / docker-compose.yml
└── .env.example                            # 环境变量模板
```

---

## 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 语言 / 运行时 | **Java 21** | 虚拟线程（`spring.threads.virtual.enabled=true`） |
| 后端框架 | **Spring Boot 4.0.5** | 构造器注入；鉴权是自研 token（不引入 Spring Security） |
| LLM 编排 | **LangChain4j 1.18.1** | `AiServices` + 本地 Tool 调用；`ChatModelListener` 采集 token 用量 |
| 模型 | DeepSeek Chat（OpenAI 兼容） | 用户自备 Key；`ai.api.url` 可换成任意兼容端点 |
| 数据库 | **H2 文件模式** | 单实例零配置；`ddl-auto=update` |
| 缓存 | **Caffeine** | 元数据缓存、委派任务表、验证码与限流计数 |
| HTTP 客户端 | **Spring `RestClient`** | 统一 8s/15s 超时；Modrinth 侧另有 429 指数退避 |
| 邮件 | Resend / Brevo / SMTP | HTTP API 优先，SMTP 兜底，用 `.env` 切换 |
| 前端 | **Vue 3 + Tailwind CSS（CDN）** | 原生 ES Modules + importmap，**无 npm / 无构建步骤** |
| 图谱 | **Vis.js Network** | 依赖 DAG 交互 |
| 测试 | JUnit 5 + Mockito | 247 个用例 + 前端三道静态门 |
| 部署 | Docker Compose | 健康检查、日志轮转、优雅停机、在线备份 |

---

## 开发

### 跑测试

```bash
./mvnw test                    # 247 个用例 + 前端三道静态门（它们挂在 test 阶段）
./mvnw clean package -DskipTests
```

前端三道门也可以单独跑（改前端时很快）：

```bash
node eval/frontend-structure-check.mjs   # 模块标签唯一 / import 目标存在 / 模板引用在作用域内 / provide-inject 契约
node eval/fe-import-lines.mjs            # import 行是否漏（--write 可自动补齐）
node eval/fe-delegation-check.mjs        # 委派取数的合并、重试、缺失上报
```

端到端评测（需要网络、会真的调用 LLM；跑之前先停掉占用 8080 的实例）：

```powershell
powershell -File eval/run-eval.ps1
```

### 代码约定

1. **不要破坏 Agent 隔离**：Architect（规划）与 Critic（审核）各自独立的 `@SystemMessage`，不合并、不共享上下文。
2. **保护 XML 正则提取**：后端用 `<tag>(.*?)</tag>` 从模型输出取值。改提示词时必须强调**不要输出 Markdown 代码块**，否则整轮退化为"解析失败"。
3. **敬畏 Modrinth 速率限制**：新增出网请求要走 `ModrinthThrottle`（或在循环里睡眠/信号量），并复用 `ModrinthApiClient` 的退避策略。
4. **并发安全**：共享状态一律 `ConcurrentHashMap` / `AtomicXxx` / Caffeine / `volatile` 换引用；每轮任务的状态保持"轮内私有"。
5. **构造器注入**，不引入臃肿第三方库；JSON 用 Jackson（`tools.jackson`），HTTP 用 `RestClient`。
6. **前端不引入构建工具**：新增交互放 `js/features/*.js`，`index.html` 只保留模板与装配；改完必须过那三道门。
7. **注释写"为什么"**：这个仓库里几乎每个反直觉的分支都对应一次真实事故，注释会写清楚"不这么做会发生什么"。

### 怎么加东西

| 想做什么 | 从哪里入手 |
|---|---|
| 加一个给规划师调用的工具 | `service/ArchitectPackTools.java`：加一个 `@Tool` 方法 + 入参校验，不要在里面直接发网络请求 |
| 加一个邮件通道 | 照 `ResendMailClient` 写一个 HTTP 客户端，然后在 `MailService` 的 provider 分支里接上 |
| 加一条冲突/依赖规则 | 改 `maa_db/rules.json`（ADMIN 规则启动时加载），或在「规则编辑」页里加 |
| 加一个前端功能模块 | `js/features/<name>.js` 导出函数，`index.html` 里 import 并 return；别再往 HTML 里塞逻辑 |
| 加一条评测断言 | 在 `eval/` 加脚本并挂进 `pom.xml` 的 exec 插件（前端三道门就是这么接的） |

---

## 安全设计

| 关注点 | 做法 |
|---|---|
| 密码存储 | **PBKDF2-HMAC-SHA256**（21 万次迭代）+ 每用户随机盐。老版本是"无盐 SHA-256"，老账号在**登录成功那一刻自动无感升级**，不需要全员改密码 |
| 密码规则 | 8~64 位且必须含字母和数字；规则只作用于**新**密码，登录不校验（否则老账号会被锁在门外） |
| 用户 LLM Key | AES-256 加密后存库，密钥种子来自 `MAA_ENCRYPTION_SECRET`，只在服务端内存使用；日志与响应里不出现明文 |
| 登录令牌 | UUID token + **空闲 30 天滑动续期**；`POST /api/user/logout` 立即作废；改密码/换绑邮箱会踢掉该账号的其它会话（保留当前设备） |
| 数据归属 | 对话消息、偏好、黑名单等接口都校验"这条数据是不是你的"，不是就返回 404（不泄露"存在但不是你的"） |
| 越权与滥用 | 验证码按邮箱限流（3 次/天、60 秒冷却、错 5 次作废）；运维端点需要 `MAA_OPS_TOKEN`；注册默认关闭 |
| 出网限流 | 全局令牌闸 + 429 退避，避免被上游限流 |

> 鉴权是自研的（token + 归属校验），没有引入 Spring Security。改这块代码时要自己保证归属校验完整 —— 现有接口都有对应的越权测试兜着（`ConversationOwnershipTest`、`EmailAuthFlowTest`、`EmailBindFlowTest`）。

---

## 常见问题

**Q：注册提示"当前不开放注册"？**
`.env` 里把 `MAA_ALLOW_REGISTRATION` 设为 `true` 并重启（Docker 下用 `docker compose up -d`，`restart` 不会重读 `.env`）。

**Q：收不到验证码邮件？**
按顺序查：① 垃圾箱；② `GET /api/ops/mail-status`（带 `X-Ops-Token`）看通道是否可用、额度还剩多少；③ `POST /api/ops/mail-test`（不传 `to` 就发给发件人自己）确认发信链路本身通不通；④ 检查发件人域名在邮件服务商侧是否验证通过（SPF/DKIM）。

**Q：提示"今日邮件发送量已达上限"？**
免费邮件额度用完了（例如 Resend 免费版 100 封/天）。验证码不会扣掉你的每日 3 次额度，第二天恢复正常。可以换 `MAA_MAIL_PROVIDER`，或参考 `MailService` 加一条自动降级。

**Q：为什么服务端不再做"沙盒检验"了？**
早期会在服务器上真开一个 MC 服务端、崩溃后自动摘模组：要维护服务器底座，而且**服务端测不出客户端侧问题**（渲染、按键、客户端模组冲突）。现在由 [MAA-Checker](https://github.com/WaitMyDawn/MAA-Checker/releases) 在本机启动客户端实例验证，结论直接对应"这台机器能不能进主菜单"。

**Q：为什么 AI 选的模组有时候不对？**
依赖关系来自 Modrinth 作者填写的元数据，作者改动就会改变闭包；"最新版本"也不区分 release/beta。最有效的修正方式是在「⚙️ 设置 → 黑名单」拉黑该模组，或在「规则编辑」页补一条规则 —— 两者都会立刻影响后续构筑。

**Q：数据存在哪？怎么备份？**
都在 `maa_db/`：`knowledge.mv.db`（用户/对话/偏好）、`rules.json`、`aliases.json`、`loader-versions.json`（`modrinth_cache.mv.db` 是纯缓存，不必备份）。可以跑 `ops/backup.sh` 做在线备份（不打断正在构筑的用户），也能用 `--cold` 走停容器冷备。

**Q：想换个 LLM 或换个模型？**
改 `ai.api.url` 指向任意 OpenAI 兼容端点即可；模型名与 Key 由用户在设置页提供。

---

## 许可与声明

- 本仓库为**保留所有权利**（All Rights Reserved），版权归作者所有。详见 [LICENSE.md](LICENSE.md)。未经许可请勿用于商业用途或再分发。
- 本项目是**非官方**工具，与 Mojang、Microsoft、Modrinth 均无隶属关系。
- 模组元数据来自 [Modrinth](https://modrinth.com) 公开 API；生成的 `.mrpack` 只包含**清单与下载地址**，不打包任何模组文件。请遵守各模组作者的授权协议。
- 用法与结果由你自行判断与承担风险；建议先用 MAA-Checker 验证再实际游玩。

---

## 反馈

问题、建议或想加的功能，欢迎开 Issue。提交代码前请先跑 `./mvnw test`，并遵守[代码约定](#代码约定)。
