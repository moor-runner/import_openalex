# 002 · sources 垂直切片：联调架构与契约

- 状态：**定稿**（唯一未决项：实现语言，见 §7）
- 日期：2026-07-17
- 配套文件：`sql/001_control_plane.sql`（本次修订）、`sql/002_data_plane_sources.sql`、`config/entities/sources.json`

## 0. 目标与范围

用最小实体 sources（实测 2026-06-26 快照：**283,287 条 / 351MB gz / 120 文件 / 120 个日分区**）把导入子系统的全部机制端到端跑真，产出可直接留用的 sources 维表。联调完成的定义 = §5 验收清单全部通过。

| 范围内 | 范围外（后续切片） |
|---|---|
| 控制面 5 表（job/file_task/watermark/dead_letter/reconcile） | works（复用全部契约，另加谓词过滤+出界删除） |
| Planner / Worker×N / Reconciler | CDC → ES / OSS（二期） |
| 幂等 upsert、断点续传、死信、对账、水位 | 增量实跑（等下个 release；§5 A7 先做空转模拟） |

sources 的特殊价值：120 个日分区反复更新同一批 source，**跨分区多版本真实存在**——去重守卫在联调中是真实受压，不是演习。

## 1. 架构

```
                  ┌───────────────────────── MySQL ─────────────────────────┐
                  │  控制面 openalex_sync            数据面 openalex         │
                  │  sync_job / file_task            sources (维表)          │
                  │  sync_watermark / dead_letter    └─ CDC(二期)只订阅这里  │
                  │  reconcile_check                                        │
                  └──────▲──────────▲───────────────────▲───────────────────┘
   manifest.json         │          │                   │ 批量幂等 upsert
S3 ─────────────► Planner┘   Worker ×N ─────────────────┘
  updated_date=*/part_*.gz    │ SKIP LOCKED 领任务 / 心跳续租
       └────── 流式(不落盘)───┘ s3 cp - | gunzip | 逐行: 解析→白名单投影→hash→攒批
                             Reconciler: 恒等式+总数+结果集指纹 → 推水位
```

三个组件是**同一个程序的三个子命令**：`plan` / `work` / `reconcile`（外加 `status`）。
多 worker = 多进程，协调完全靠 MySQL `SKIP LOCKED`（001 伪 SQL A/B/C/D），进程间零通信。

- **Planner**：读 per-entity `manifest.json` → 建 `sync_job` + 逐文件 `file_task`（幂等，见 C6）。
- **Worker**：循环领任务；单文件处理 = 流式下载→解压→逐行（解析→投影→hash）→攒批 upsert；完成后**一次性**写计数并置 DONE。
- **Reconciler**：job 全部 DONE 后跑三层对账，全 PASS → job SUCCEEDED + 同事务推水位。

## 2. 数据事实（联调基线，2026-06-26 快照实测）

- manifest 顶层 `record_count=283,287` 恒等于 Σ per-file `record_count`（已验证）→ 实体级对账基准。
- 字段全集 **36 个**，瘦分区（2026-02-09）与肥分区（2026-06-26）取样字段集合完全一致 → 白名单封口。
- 瘦记录均 491B / 肥记录均 23KB；肥记录中 `topic_share` 42.7% + `topics` 42.0% + `counts_by_year` 11.9%。
- `updated_date` 形如 `2026-06-26T10:02:15.000Z`（UTC、毫秒位为 000）。
- `type` 观测值：journal / conference / repository（枚举开放，按字符串存）。

## 3. 契约

### C1 · ID 契约
`https://openalex.org/S15574646` → 去域名与实体前缀 → `BIGINT 15574646` 为主键。全 ID 可无损重建（`'https://openalex.org/' + prefix + id`）。所有实体同规则，前缀在 config 中声明。

### C2 · 版本契约
`source_updated_at` = 上游 `updated_date`（ISO8601 UTC，微秒截断到毫秒）→ `DATETIME(3)`，全链按 UTC 理解，不做时区换算。列语义 = **最近一次引起投影变化的上游时间**（写抑制时不推进，见 C3）。增量水位不依赖此列——水位在 `sync_watermark`，粒度是分区/文件。

### C3 · 幂等契约（两层防线）

**第一层（客户端预读，优化 + 精确观测）**：每批先
`SELECT id, source_updated_at, content_hash FROM sources WHERE id IN (批内 ids)`，
批内同 id 先按最大 ts 去重，然后分类：

| 分类 | 条件 | 动作 |
|---|---|---|
| NEW | 无现存行 | 发送 |
| APPLY | 新 ts > 存量 ts 且 hash 不同 | 发送 |
| STALE | 新 ts ≤ 存量 ts | 跳过（计数） |
| SUPPRESS | 新 ts > 存量 ts 且 hash 相同 | 跳过（计数，写抑制） |

**第二层（SQL 守卫，正确性兜底）**——只对 NEW+APPLY 发送：

```sql
INSERT INTO openalex.sources
  (id, source_updated_at, content_hash,
   display_name, source_type, issn_l, country_code,
   is_oa, is_in_doaj, works_count, cited_by_count, payload)
VALUES (...), (...), ...  AS new            -- MySQL 8.0.19+ 行别名; VALUES() 已废弃
ON DUPLICATE KEY UPDATE
  display_name   = IF(new.source_updated_at > sources.source_updated_at, new.display_name,   sources.display_name),
  source_type    = IF(new.source_updated_at > sources.source_updated_at, new.source_type,    sources.source_type),
  issn_l         = IF(new.source_updated_at > sources.source_updated_at, new.issn_l,         sources.issn_l),
  country_code   = IF(new.source_updated_at > sources.source_updated_at, new.country_code,   sources.country_code),
  is_oa          = IF(new.source_updated_at > sources.source_updated_at, new.is_oa,          sources.is_oa),
  is_in_doaj     = IF(new.source_updated_at > sources.source_updated_at, new.is_in_doaj,     sources.is_in_doaj),
  works_count    = IF(new.source_updated_at > sources.source_updated_at, new.works_count,    sources.works_count),
  cited_by_count = IF(new.source_updated_at > sources.source_updated_at, new.cited_by_count, sources.cited_by_count),
  payload        = IF(new.source_updated_at > sources.source_updated_at, new.payload,        sources.payload),
  content_hash   = IF(new.source_updated_at > sources.source_updated_at, new.content_hash,   sources.content_hash),
  source_updated_at = IF(new.source_updated_at > sources.source_updated_at,
                         new.source_updated_at, sources.source_updated_at);
```

**赋值顺序陷阱（必须遵守）**：`ON DUPLICATE KEY UPDATE` 的赋值从左到右依次生效，先赋的新值会被后面的条件看到。因此守卫条件**只允许引用 `source_updated_at`，且该列必须最后赋值**。hash 若参与 SQL 条件会与自身赋值互锁产生错误结果——这是把 hash 判断上移到客户端预读的第二个原因（第一个是精确计数）。

**为什么两层都要**：两个 worker 并发处理同一 id 的不同版本时，预读可能拿到旧值、误判 APPLY——SQL 守卫仲裁（ts 大者胜），最多损失一次抑制优化，不损正确性。口号：**at-least-once 投递 × 幂等写 = 观测上的 exactly-once**。

**affected_rows 断言**：每批期望 `affected_rows = NEW + 2×APPLY`（insert=1/update=2/no-op=0）。不符 = 并发撞车或批内残余重复（合法，计数记日志）或 bug。幂等重跑时全 job `Σaffected_rows = 0`。

### C4 · content_hash 契约
64 位无符号整数（`sha256 截前 8 字节`或 `xxhash64`，实现内自洽即可）。输入 = 规范化投影串：按 config 声明顺序遍历 typed + payload 字段；每字段取值的紧凑 JSON（sorted keys、`ensure_ascii=False`）；NULL 用哨兵 `\x00`；字段间 `\x1f` 分隔。**只由导入器从上游记录计算，永不从 MySQL 读回的 payload 重算**（JSON 类型会重排键序）。算法升级的代价 = 一轮 STALE/异常计数噪声，零数据风险（ts 未变，守卫拦截）。

### C5 · 白名单防腐契约
`consumed(2) + typed(8) + payload(24) + drop(2) = 36 = 实测字段全集`（config 为唯一权威）。
- 快照中出现**不在任何集合**的字段：记录仍按白名单正常处理，同时发 `schema_drift` 死信采样告警（每 file×field 只记一条，防刷屏）。上游加字段 ≠ 事故，但必须被看见。
- drop 依据：`works_api_url` 可由 id 推导；`topic_share` 与 `topics` 同构冗余（肥记录 42.7% 字节）。
- v1 只做**顶层字段粒度**，载荷内部（如 topics 内嵌 subfield/field/domain）不裁剪——载荷内裁剪属 works 切片课题。

### C6 · 文件任务契约
- 任务身份 = `(job_id, s3_key)`；文件指纹 = `(content_length, record_count)`（manifest 无 etag）。planner 重跑幂等：已存在且指纹一致 → 不动；指纹变化的 DONE 任务 → 重置 PENDING（release 重排场景）。
- at-least-once：领取/心跳/租约回收按 001 伪 SQL A/B/C；崩溃恢复 = 租约到期回收，文件重跑安全（C3）。
- **计数只在 DONE 时一次性写入**：重试 attempt 从零重算，不做增量累计，杜绝重复计数污染。计数口径 = 本次成功 attempt（重跑全 upsert 的文件会呈现 read=stale，恒等式依然成立）。
- `s3_key` 允许 `file://` scheme：联调毒行夹具专用，下载器按 scheme 分派（`s3://`→`aws s3 cp -`，`file://`→本地读）。

### C7 · 对账与水位契约
1. **FILE_ACCOUNTING（精确断言）**：每文件 `read = upserted + stale + suppressed + filtered + deleted + failed` 且 `read == manifest_records`。
2. **实体级（精确断言）**：`Σ records_read == manifest.record_count == 283,287`。
3. **TOTAL_COUNT（容差告警）**：`COUNT(*)` vs API `https://api.openalex.org/sources` 的 `meta.count`——多版本去重后行数 ≤ Σread 属预期，与 API 的差异反映 release/API 时差，只告警不阻断。
4. **结果集指纹**（等价性比对用）：
   ```sql
   SELECT COUNT(*) AS n,
          SUM(CRC32(CONCAT(id,'|',source_updated_at,'|',content_hash))) AS fp
     FROM openalex.sources;
   ```
全部 PASS → job 置 SUCCEEDED，**同事务**推进 `sync_watermark`。manifest 是 commit marker：只处理 manifest 列出的文件，桶里多余文件不看。

## 4. 数据面设计要点

四层分类落位（对照 `sql/002`）：

| 层 | 列 | 说明 |
|---|---|---|
| 控制列 | source_updated_at / content_hash / first_imported_at / row_updated_at | 幂等与观测 |
| 谓词列 | （sources 无） | works 切片 = publication_year |
| 类型化业务列 | display_name / source_type / issn_l / country_code / is_oa / is_in_doaj / works_count / cited_by_count | 克制，按消费者论证 |
| JSON 载荷列 | payload（24 字段） | 不透明透传，ES 文档素材 |

- **分库理由**：CDC（二期）按库订阅 `openalex.*`，控制面高频状态写（心跳、计数）不进 CDC 视野；两库 binlog 策略独立。
- 起步**零二级索引**；`payload` 键序被 MySQL JSON 类型重排属预期（C4 已规避其影响）。

## 5. 验收清单（联调完成的定义）

| # | 场景 | 操作 | 断言 |
|---|---|---|---|
| A1 | 全量跑通 | `plan` → `work --workers 4` → `reconcile` | 120 task 全 DONE；Σread=283,287；FILE_ACCOUNTING 全 PASS；记录指纹 fp₀ |
| A2 | 幂等重跑 | 清控制面 job/task（**不清数据面**）→ 重跑 A1 | 指纹 == fp₀；`Σaffected_rows_sum = 0`；upserted=0 |
| A3 | 断点续传 | work 中途 `kill -9` 一个 worker（联调租约缩至 60s/心跳 20s） | 任务被回收重派；A1 断言仍全过 |
| A4 | 并发等价 | workers=1 跑出 fp₁ → `TRUNCATE sources` + 清控制面 → workers=8 跑出 fp₂ | fp₁ == fp₂（乱序安全的直接证据） |
| A5 | 毒行死信 | `file://` 夹具：2 好行 + 3 毒行（断裂 JSON / 缺 id / 坏时间戳） | task 照常 DONE；dead_letter=3 且 stage 正确；恒等式含 failed=3 平衡 |
| A6 | 可观测 | `status` 子命令 | 单条 SQL 输出各状态文件数/行数/字节/死信（见下） |
| A7 | 增量空转 | 水位就位后对同一 manifest `plan --incremental` | 0 新任务，job 直接 SUCCEEDED |

```sql
-- A6 的 status 查询
SELECT status, COUNT(*) files, SUM(records_read) rows_read,
       SUM(records_upserted) upserted, SUM(records_failed) dead,
       ROUND(SUM(file_bytes)/1e6,1) mb
  FROM openalex_sync.file_task WHERE job_id = ? GROUP BY status;
```

每条验收的执行记录（命令 + 断言输出）存 `docs/acceptance/`——这是面试素材，不是负担。

## 6. 运行参数（联调默认）

| 参数 | 值 | 说明 |
|---|---|---|
| 批大小 | 1000 行/语句，1 批 = 1 事务 | works 期再调 |
| workers | 4 | sources 体量下已过剩 |
| 租约/心跳 | 15min / 5min（联调 60s / 20s） | A3 用短租约 |
| max_attempts | 3 | 超限进 FAILED |
| 下载 | 流式 `aws s3 cp <key> - --no-sign-request`，解压数据不落盘 | `file://` 走本地读 |
| 会话 | `SET SESSION sql_log_bin=0` | 联调无 CDC 消费者；月度增量期必须开 binlog |

前提：MySQL 8.4 已运行（本机已确认存活），需 root 凭据执行 `sql/001` + `sql/002`（环境中未发现免密配置，待提供）。

## 7. 决策记录

**本次新定**：两层幂等防线（客户端预读分类 + SQL ts 守卫）；hash 判断上移客户端（赋值顺序陷阱 + 精确计数）；计数 DONE 时一次写；`file://` 夹具进契约；控制面/数据面分库；36 字段白名单封口，drop `works_api_url` + `topic_share`；`filter_version` 默认改 `none.v1`。

**已拍板（2026-07-17）**：实现语言 = **Java 21**（裸 JDK + Maven，无 Spring；二期自研 CDC 走 mysql-binlog-connector-java 生态）。工程骨架在 `importer/`，纯函数核心（Projector/UpsertSql/LineSource/EntityConfig）已实现并有 7 项契约测试护住；Planner/Worker/Reconciler 为契约化桩，待 MySQL 凭据到位后实现。

## 8. 001_control_plane.sql 本次修订记录

1. 头注释：设计原则 1 由"数据面不过 MySQL"改为控制面/数据面分库（架构定案对齐）。
2. `sync_job.filter_version` 默认值 `pubyear_ge_2019.v1` → `none.v1`。
3. `file_task` 计数列：`records_indexed/records_stale`（ES 语义）→ `records_upserted/records_stale/records_suppressed + affected_rows_sum`（MySQL 语义），恒等式注释同步。
4. `dead_letter.stage`：`INDEX` → `UPSERT`；error_type 示例与 payload 注释去 ES/MinIO 化。
5. `reconcile_check` 三层说明改为 MySQL 口径（TOTAL_COUNT 明确为容差告警）。
6. `es_index_registry` 标注"二期使用，切片不触碰"。
