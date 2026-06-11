<p align="center">
  <h1 align="center">MAA — Minecraft 整合包智能构筑系统</h1>
  <p align="center">
    <strong>M</strong>inecraft <strong>A</strong>I <strong>A</strong>gent · 基于 Agentic RAG 的多智能体模组整合包生成器
  </p>
</p>

---

> 用自然语言描述你想要的 Minecraft 整合包，AI 自动搜索、筛选、组装、校验，最终生成可直接下载的 `.mrpack` 文件。

---

## 这是什么？

你只需要告诉 MAA「我想玩工业科技加魔法的包，带点地牢探险」，它会自动：

1. **理解意图** — AI 规划师分析需求，推荐核心模组
2. **Modrinth 搜索** — 并发请求 Modrinth API，按关键词多路召回候选模组
3. **智能筛选** — AI 审核员剔除冲突、挑选最佳组合
4. **依赖穿透** — BFS 递归解析前置依赖，结合本地知识库排查已知冲突
5. **沙盒验证** — 在真实 Minecraft 服务端中启动，崩溃后 AI 自动诊断修复
6. **一键下载** — 生成标准 `.mrpack` 文件

辅助减轻手动查 Modrinth 的工作量，但仍需结合本地知识库来保证依赖关系的准确性。

---

## 四阶段智能体管线

```
用户输入: "工业科技 + 魔法主题"
         │
         ▼
  ┌──────────────────────────────────────┐
  │  1. Architect Agent                 │
  │  理解需求 → 核心模组 → XML 蓝图     │
  │  输出: core_mods, search_intents    │
  └──────────────┬───────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────┐
  │  2. Java Retriever                  │
  │  虚拟线程并发 → Modrinth 多路召回   │
  │  HitScore 评分 → 候选池 (Top150)    │
  └──────────────┬───────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────┐
  │  3. Critic Agent                    │
  │  审核候选 → 剔除冲突 → 精选模组     │
  │  输出: approved_mods                │
  └──────────────┬───────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────┐
  │  4. Dependency Engine               │
  │  BFS 依赖穿透 → 本地知识库冲突检测  │
  │  非官方版本抢救 → Caffeine 防爆缓存 │
  └──────────────────────────────────────┘
```

**三个 AI Agent 严格隔离**，各自独立的 System Prompt，不共享上下文——职责分离防止幻觉扩散。

> **局限说明**：模组依赖的正确性依赖 Modrinth 上作者填写的元数据，以及本地知识库的补充规则。沙盒校验能覆盖服务端启动阶段的大部分崩溃，但对于创建世界阶段的卡死问题，目前仍需人工排查。

---

## 快速开始

### 环境要求

- **JDK 21+**
- **Maven**（或使用 `./mvnw` wrapper）

沙盒校验功能需要预先准备的 Minecraft NeoForge 服务端目录（可选）。

### 启动

```bash
git clone https://github.com/your-username/Minecraft-AI-Agent.git
cd Minecraft-AI-Agent

cp .env.example .env
# 编辑 .env 填入密钥（也可在 Web UI 中由用户自行配置）

./mvnw spring-boot:run
```

浏览器打开 `http://localhost:8080`，注册账号后在设置页输入 DeepSeek API Key 即可使用。

### 用户使用自己的 API Key

系统默认不配置 LLM Key。**每个用户在 Web UI 中设置自己的 API Key**，Key 经 AES-256 加密存储在数据库，浏览器不可见。

---

## 技术栈

| 层 | 技术 | 说明 |
|---|------|------|
| 后端框架 | Spring Boot 4.0 + Java 21 | 虚拟线程并发，构造器注入 |
| AI 框架 | LangChain4j 0.29 + DeepSeek Chat | 三 Agent 架构，独立 System Prompt |
| 数据库 | H2 嵌入式 (文件模式) | 零配置，`ddl-auto=update` |
| 缓存 | Caffeine (60min TTL, 10k max) | Modrinth API 防爆 |
| HTTP | Spring RestClient | 指数退避 (429 处理) |
| 前端 | Vue 3 CDN + Tailwind CSS CDN | 单文件 SPA，无构建工具 |
| 图谱 | Vis.js Network | 依赖关系 DAG 可视化 |
| 部署 | systemd + Nginx | 支持 Docker / 容器化 |

---

## 项目结构

```
Minecraft-AI-Agent/
├── src/main/java/yagen/waitmydawn/maa/
│   ├── MinecraftAiAgentApplication.java    # 入口 + .env 加载器
│   ├── config/
│   │   └── AppConfig.java                  # Caffeine 缓存 + RestClient
│   ├── controller/                         # REST 控制器
│   │   ├── ChatController.java             # 核心四阶段管线编排
│   │   ├── ModpackController.java          # 预览/构建/建议引擎
│   │   ├── UserController.java             # 注册/登录/加密 (AES-256)
│   │   ├── PreferencesController.java      # 偏好 CRUD + 导出导入
│   │   ├── KnowledgeController.java        # 知识库查询
│   │   └── KnowledgeFeedbackController.java # 用户反馈规则
│   ├── service/                            # 业务逻辑层
│   │   ├── AiAgentService.java             # Architect/Critic/Doctor Agent
│   │   ├── DependencyEngine.java           # BFS 依赖穿透 (Semaphore=5)
│   │   ├── ModrinthApiClient.java          # 指数退避 API 客户端
│   │   ├── ModrinthDownloader.java         # 并发 JAR 下载 + ZIP 校验
│   │   ├── MrpackParser.java               # .mrpack 文件解析
│   │   └── KnowledgeDb.java                # 硬编码知识库 (CommandLineRunner)
│   ├── model/                              # JPA 实体 + 仓库
│   │   ├── User.java, ModPreference.java, ModBlacklist.java
│   │   ├── KnowledgeRule.java (含 ADMIN/MODRINTH/USER_FEEDBACK)
│   │   └── ...
│   └── check/                              # 沙盒校验子系统
│       ├── SandboxController.java          # SSE 流式校验 (30min 超时)
│       ├── SandboxTesterService.java       # MC 服务端生命周期管理
│       ├── SelfHealingEngine.java          # 五级诊断自愈循环 (max 20轮)
│       └── CrashLogParser.java             # 多模式崩溃日志解析
├── src/main/resources/
│   ├── application.properties
│   └── static/index.html                   # 完整 SPA 前端
├── .env.example                            # 环境变量模板
└── pom.xml
```

---

## 核心功能

### 构筑引擎

- 用户偏好学习 — 每次构筑后自动更新偏好权重，影响后续搜索方向
- 多样性算法 — 多路召回 + HitScore 评分 + 区间随机扰动
- 偏好权重可调 (0~1) — 控制历史偏好对构筑的影响力
- 模组黑名单 — 级联排除（被排除模组的前置跟随排除，保持依赖完整性）

### 沙盒自愈校验

- 真实 Minecraft 服务端物理碰撞测试
- 五级诊断管道：代码正则匹配 → 日志模式分析 → LLM 兜底
- 自动移除问题模组并重试（最多 20 轮，含安全熔断）
- SSE 实时进度推送

### 用户系统

- SHA-256 密码哈希存储
- 个人 API Key AES-256 加密存储（数据库存密文，浏览器不可见）
- 偏好/黑名单/权重可导出为 JSON 文件，支持导入
- 对话历史持久化，可回放

### 知识规则

- 管理员硬编码规则 (ADMIN，启动时自动加载)
- Modrinth 爬虫同步规则 (MODRINTH)
- 用户社区反馈规则 (USER_FEEDBACK，需 ≥3 人确认生效)
- 规则编辑器支持实时预览模组卡片

---

## 安全

| 问题 | 说明 |
|------|------|
| 浏览器能获取 API Key 吗 | 不能。Key 仅输入时经浏览器，后端 AES-256 加密后丢弃原文 |
| 数据库存储 | 仅存 AES-256 密文，密钥来自 `MAA_ENCRYPTION_SECRET` 环境变量 |
| 密码存储 | SHA-256 哈希，非明文 |
| Session | UUID token，存在服务端 `ConcurrentHashMap`，前端 `localStorage` 保存 |

---

## 开发

```bash
git clone https://github.com/your-username/Minecraft-AI-Agent.git
# IDE 打开后运行 MinecraftAiAgentApplication
./mvnw clean package -DskipTests   # 打包
```

**设计原则**：

- Architect 和 Critic 必须独立，不能合并 System Prompt
- LLM 输出为纯 XML（不含 markdown），后端用正则 `<tag>(.*?)</tag>` 提取
- Modrinth API 请求必须复用 `ModrinthApiClient` 的指数退避算法
- 共享变量必须用 `ConcurrentHashMap` 或 `AtomicInteger`
- JSON 统一用 Jackson，HTTP 统一用 Spring RestClient
