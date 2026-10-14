# 构建能力评测

`golden-set.json` 的 `cases` 是可执行用例；`badCases` 是缺陷历史记录。
旧版 v1.3 保持冻结。`golden-set-v2.json` 是独立增量集：18 个场景、22 次业务请求，
其中三个场景包含连续回合。两个题集应分别运行和统计，不能混用历史通过率。
`offline` 表示不调用模型的预览层，仍依赖 Modrinth 实时数据，不是固定数据离线测试。

`golden-set-v3.json` 是最终需求覆盖的独立增量集：12 个场景、14 次业务请求。包含自动化/物流/仓储、职业战斗、烹饪装饰、维度范围、包内联动、跨轮选版、明确解除和短输入。v1/v2 的旧断言不因新工作流改写或降阈值，v3 单独记录通过率。

`golden-set-v4.json` 是工作流与优化组合增量集：12 个场景、15 次业务请求。覆盖服务端/客户端用途、互补优化、局部冷热偏好、现代化UI身份、同词不同功能、交通航海与工业仓储复合目标、短输入确认、大幅世界生成修改确认及解除目标。

确认回合使用 `confirmation`，不另写 prompt。BASIC 的 answer 是测试作者明确给出的答复；OVERLAP 的 KEEP_ALL 是测试作者主动保留全部的操作，不是倒计时默认确认。暂停时继续保留原工作区、uuid和装配输入，确认后再接收最终清单；缺少票据不跳过回合或按通过处理。真实 UI 的多选、取消和队列失败仍由浏览器与固定事实测试补充。

`assert.optimization` 检查优化报告、目标侧别用途、最终版本留存、渲染器数量及软参考适用范围；缺少报告或选版必须失败。它不检测实际性能提升。四套题集分开统计，不从固定事实回归通过数推算模型端到端通过率。

## 运行

在进程环境中配置 `MAA_EVAL_AUTH_TOKEN`，值为已登录专用测试账号的 token。
模型层默认使用该账号保存的个人 API Key。需要覆盖 key 时可配置
`DEEPSEEK_API_KEY`，并在被测应用显式开启 `maa.eval.allow-key-override=true`。
执行器和运行脚本不读取 `.env`，不打印凭据。测试账号应避免同时开展其他构建操作。

```powershell
./eval/run-eval.ps1 -NoStart -Layer offline -Cases O02,O07 -Repeat 3
./eval/run-eval.ps1 -NoStart -Set eval/golden-set-v2.json -Layer all -Label coverage-v2
./eval/run-eval.ps1 -NoStart -Set eval/golden-set-v4.json -Layer agent -Cases W01,W02,W09 -Label workflow-v4
./eval/run-eval.ps1 -NoStart -Set eval/golden-set-v2.json -Layer agent -Cases T01,T02,T03 -Repeat 3 -Label persistence-v2
./mvnw.cmd -o test -Dtest=GoldenSetEval -Deval=true -Deval.layer=agent -Deval.repeat=3
```

客户端处理 `queued`、`running`、`need-data`，只提交一次业务请求；任务超时或
协议失败时尝试取消。Modrinth 事实由评测 JVM 获取，回执标记为 `eval-jvm`，
不会携带应用登录 token 或模型 key。它验证委派协议与构建流程，不验证浏览器
CORS、用户网络、文件保存或实际浏览器请求归属；这些仍需要浏览器端到端测试。

## 指标口径

指标版本 3 的主通过率要求每题的所有重复尝试均通过，每次失败都进入该题失败列表。
JSON 保存每次尝试的状态、耗时、数量、环境、trace 与失败原因。
token、模型调用、工具调用和依赖耗时累计全部尝试；P50/P95 基于单次尝试耗时，
而不是每题平均耗时。缺失 trace 的尝试不能提供完整成本，应结合 trace 覆盖率解释。

产物为 `target/eval/<label>-<timestamp>.json`、`target/eval/summary-v3.csv` 和
`eval/results/ablation-v3.csv`。保存题集版本与 SHA-256 指纹，旧 CSV 保留。
多轮题每次尝试的耗时是完整场景耗时，成本汇总所有回合；每轮原始回复、trace、环境、数量
和失败单独记录。重复尝试使用不同 uuid，同场景各轮携带实际返回的清单、环境与约束。
不重新手写下一轮状态；没有构筑状态的中间轮终止后续轮并记失败，避免静默漏测。
能力维度可以重叠，各维度通过数不能相加作为总通过数。人工复核标为 NOT_ASSESSED，
不计入自动分数。不同题集、指标版本和单轮/多轮延迟不可直接比较。
`constructionStatus` 独立于断言分数：缺少必选、静态解析不完整或数量未满足记为 UNMET；
协议缺少权威判断或拒答记为 NOT_ASSESSED；MET_STATIC_CONSTRAINTS 只表示这几项静态约束满足，
不能解释为玩法质量、运行验证或全部用户需求达成。多轮汇总对应最后一轮状态，成本仍累计所有轮。
同一进程的缓存和依赖膨胀系数会影响重复结果，冷、热缓存测试应分开标记。

## 新断言

`assert.facts` 使用 JSON Pointer：模型响应对应 `/trace`、`/constraints`、`/dependency_audit`，
预览响应对应实际 JSON 字段，如 `/closure/status`。支持 equals、contains、absent、min、max。
集合断言仅接受数组，范围断言仅接受数字；缺字段必失败，不以空集合或零值替代。
用例 ID 重复、未知断言字段或操作名在发出 HTTP 请求前报错。
`quantityReported` 要求有权威数量判断，未满足时可见回复必须报告数量约束；
`resolutionReported` 在解析不完整时要求可见依赖诊断。
`visibleReplyContains` 只检查去除协议标签的可见回复，不能靠隐藏 trace 中的词语通过。

`assert.coverage` 对最终 `requirement_report` 检查原文账本覆盖、指定原文片段、有入包依据的条数、点名身份缺口、补缺上限及依据是否仍在清单和选版快照中。`sourcesContain` 为原文片段数组，`minSupporting`、`maxRepairAdds` 为非负整数；`noMissingNamed`、`retainedEvidence` 为布尔值。缺少报告、账本或选版不能按空值通过。它验证协议及事实留存，不证明模型用途解释正确或玩法丰富。

多轮执行器传递服务端实际返回的 `assembly`。JSON 展示协议与 Markdown 文案分开检查；静态依赖诊断也可由有效的 `build_report.issues` 展示。`contentEvidenceStatus` 与 `constructionStatus` 独立记录：即使有用途依据也只记 `SUPPORTING_EVIDENCE_ONLY`，不记玩法已满足。

## 容量冒烟

显式运行 `CapacitySmokeEval`，参数为 `-Deval.capacity=true -DargLine="-XX:ActiveProcessorCount=2 -Xmx1536m"`。产物 `target/eval/capacity-smoke.json` 记录 100 个合成账号、8 个工作名额、128 个队列容量下的排队、等待选择释放和取消结果，以及合成延迟和 JVM 堆使用。默认测试不执行该基准。

合成任务只做 30～60ms 等待及票据登记，不调用 HTTP、模型或 Modrinth；这些数值只能验证队列机制，不能外推真实构建吞吐量。JVM 的两核及堆限制不等于操作系统 CPU 配额与 4GB 内存硬限制。上线验收还需在目标服务器混合测试轻请求、图谱、冷/热缓存和真实构建；100 个在线用户与 100 个同时构建必须分别衡量。排队期间也计入当前 20 分钟任务期限，长构建积压可能先超时，队列容量不是可承诺的等待能力。

## 覆盖与限制

| 能力 | 实时增量用例 | 固定数据回归补充 |
|---|---|---|
| 多核心与真实冒险需求 | G01 | StructuredAssemblyTest |
| 必选预检阻塞与正常构筑 | required-preflight-v1.json 的 R01、R02 | RequiredModPreflightTest、ChatPlanningBoundaryTest |
| 附属关系、误召回与预算 | G02、G05 | RetrievalBoundaryTest、RetrievalPolicyTest |
| 小增量与数量口径 | G03、G09、G10 | CategoryQuotaTest、StructuredAssemblyTest |
| 环境继承与否定句 | G04 | ChatPlanningBoundaryTest |
| 幻觉与范围边界 | G06、G11、G12 | agent 协议与格式校验测试 |
| 排除与多轮约束 | G07、G08、T01–T03 | BuildConstraintsTest、StructuredAssemblyTest |
| 静态依赖闭包 | P01–P03 | DependencyClosureTest、ModpackExportClosureTest |
| 精确导入与再导出 | 浏览器 import-v1 报告 | ImportRoundTripTest、PackManifestSecurityTest |
| Critic veto、失效输出与重试 | G01 的条件诊断 | StructuredAssemblyTest、AiAgentStructuredOutputTest |
| 429、取消、队列与身份隔离 | 不用实时限流故障作为稳定题目 | DelegationTasksTest、EvalHarnessTest、前端委派/安全/队列检查 |

实时断言验证当前上游结果；版本冲突不写成固定项目永久期望，缺口诊断用条件断言，
固定冲突、网络故障和畸形数据在接口回归中验证。黄金集不证明玩法质量、全场景覆盖、
百人并发容量、浏览器取数归属或实际启动成功。主题质量需人工复核，启动由 MAA-Checker 负责。
后续可按失败样本追加版本迁移、复杂类别比例、多核心联动和别名用例；冻结后不降阈值掩盖失败。

必选预检属于工作流行为变更：核心未核实则不提交部分清单。旧 v2.0 的 G01 期待部分清单，
在新行为下可能失败，保留作历史基线；新增 required-preflight-v1.json 单独断言阻塞及正常放行。
运行时用 `-Set eval/required-preflight-v1.json`，不要将旧通过率直接解释为新工作流回归率。
