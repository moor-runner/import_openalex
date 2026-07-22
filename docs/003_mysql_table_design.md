# 003 · MySQL 表设计：设计思想与逐表解读

- 状态：定稿
- 日期：2026-07-22
- 覆盖对象：控制面 `openalex_sync` 六表（`sql/001_control_plane.sql`）+ 数据面 `openalex.sources`（`sql/002_data_plane_sources.sql`）
- 本文是 DDL 的"为什么"层：列定义以 sql/ 下 DDL 为准，行为契约以 `docs/002`（C1–C7）为准，本文负责把跨表的设计思想和每张表的取舍讲清楚。

---

## 0. 总设计思想

### 0.1 三条公理

1. **控制面与数据面分库**（`openalex_sync` / `openalex`）：二期 CDC 按库订阅数据面，控制面的高频状态写（心跳、计数、租约）永远不进 CDC 视野；两库 binlog 策略独立（联调期数据面 `sql_log_bin=0`，增量期必须开）。
2. **一切围绕幂等重放 + 断点续传 + 可对账**：任何表、任何列的存在都要能挂到这三件事之一上。
3. **文件 = 最小任务单元 = 最小重放单元 = 最小对账单元**：三个"最小"对齐到同一粒度，是整个控制面简单性的来源——checkpoint 不用细于文件，对账不用粗于文件。

### 0.2 一个定理：at-least-once × 幂等写 = 观测上的 exactly-once

"精确一次执行"在这个系统的任何层面都不存在：planner 会被 cron/手滑/崩溃恢复重复执行，worker 会因租约回收重复处理文件，attempt 会重试。所以每一层都按 at-least-once 设计，并各自配一个幂等器：

| 层 | 重复执行从哪来 | 幂等器 | 落在哪 |
|---|---|---|---|
| Planner | 崩溃重跑 / cron 探测 / release 竞态 | job 身份键 + task 身份键 + 指纹收敛 | `sync_job` 唯一键、`file_task` 唯一键 + `(file_bytes, manifest_records)` |
| 任务/文件 | 租约到期回收、重派 | 不防重复执行，只保证重复无害（下沉到行级） | `file_task` 状态机（status / lease / attempts） |
| 行 | 文件重跑、并发 worker | ts 守卫 + hash 预读（契约 C3） | `sources.source_updated_at` / `content_hash` |
| 计数 | attempt 重试 | DONE 时一次性写，从零重算 | `file_task.records_*` |

关键取舍在任务层：**不试图阻止一个文件被处理两次**（那需要分布式协调），只保证处理两次结果收敛——正确性责任由行级守卫兜底，重跑的文件全部收敛为 STALE，`Σaffected_rows = 0`。

### 0.3 一个同构模式：身份键 + 版本判据 + 守卫写入

行级幂等和任务级幂等是**同一个模式在两个粒度上的实例化**：

| | 身份（存在性） | 版本判据（有效性） | 守卫写入（收敛动作） |
|---|---|---|---|
| 行级 | `sources.id` 主键 | `source_updated_at`（+`content_hash`） | ts 大者胜（有序判据） |
| 任务级 | `file_task (job_id, s3_key)` 唯一键 | `(file_bytes, manifest_records)` 指纹 | 变则重置重做（无序判据） |

两层的实质区别在**判据的性质**：

- 行级判据**有序**（时间戳）：能判"谁更新"，乱序重放自动收敛，不需要重做任何东西。
- 任务级判据**无序**（manifest 不提供时间戳和 etag，指纹只能判"变没变"）：唯一动作是"检测到变化 → 整个文件重做"。整文件重做之所以敢做，正因为行级那层有序判据会把重做兜成收敛。

指纹本质是**穷人的 etag**：理想指纹是内容哈希，manifest 不给（`file_task.etag` 列即为此留的空位），退而用 manifest 自带的两个整数 `(content_length, record_count)` 做内容近似——零额外 S3 请求，联合判别力足够；碰撞的兜底是快照的追加式结构（变更会出现在新分区）+ 对账层总数核验。

### 0.4 两根正交的时间轴

系统里有两个都叫"范围"的东西，必须劈开：

| | 内容范围（谓词） | 同步窗口（分区游标） |
|---|---|---|
| 时间轴 | `publication_year`（论文**发表**年份） | `updated_date`（快照**更新**日期） |
| 选什么 | 选**记录**（这条 work 要不要留） | 选**文件**（这个分区要不要处理） |
| 落在哪 | `sync_job.filter_version` + config `predicate` | `sync_job.partition_from/to` + `sync_watermark` |
| 作用面 | 只作用于 works（维表 `predicate=null`） | 所有实体 |
| 谁决定 | 业务拍板，可调（调整必须升 `filter_version`） | 水位机械推进 |

一篇 1995 年发表、昨天被上游修订的 work：内容轴上出界（`publication_year=1995`）、同步轴上在窗口内（`updated_date=昨天`）——它会被下载、解析，然后在投影阶段被谓词过滤（全量）或触发出界删除（增量）。两轴各走各的，互不解释。

### 0.5 crash-only：恢复路径 = 正常路径

Planner 是**收敛器而非创建器**（"让 file_task 表收敛到 manifest"），worker 崩溃靠租约超时自然回队，计数靠 DONE 一次性写保持自洽。于是整个系统的恢复手册只有一句："**不管发生了什么，再跑一遍。**"崩溃恢复和日常运行是同一条代码路径，因此恢复路径永远被日常执行所测试。

---

## 1. 分库设计

| 库 | 表 | 写特征 | binlog |
|---|---|---|---|
| `openalex_sync`（控制面） | 六张 | 高频小写（心跳/领任务/计数） | 无 CDC 消费者，策略随意 |
| `openalex`（数据面） | `sources`（后续 `works` 等） | 批量 upsert | 二期 CDC 唯一订阅面；联调期 `sql_log_bin=0`，增量期必须 ROW 格式开启 |

分库不是规模问题（都在同一实例），是**订阅面隔离**问题：CDC 按库过滤最简单可靠，控制面的租约心跳若混进 binlog，就是纯噪声流量。

---

## 2. 控制面逐表解读

### 2.1 `sync_job` — 批次账本

一次全量 / 一次月度增量 / 一次谓词扩容回填 = 一行。

- **唯一键 `(entity, snapshot_date, job_type, filter_version)` 是 planner 幂等的第一道锚**：对同一快照、同一谓词版本重跑 plan，命中的是同一个 job 行，不会长出第二个批次。
- **`filter_version` 进身份**：谓词是 job 身份的一部分。改谓词（如 `pubyear_ge_2019.v1` → `pubyear_ge_2015.v2`）不是在旧 job 上修补，而是产生新的 BACKFILL job——谓词变更的影响面（回填哪些分区、删哪些行）由新 job 完整承载，可追溯。
- **`job_type` 三值**：FULL（首次基线）/ INCREMENTAL（水位之后的新分区)/ BACKFILL（谓词升版触发的历史回补）。三者共用全部执行机制，区别只在 planner 选文件的方式。
- **`partition_from / partition_to`**：同步窗口游标（0.4 的第二根轴），增量 job 只处理 `updated_date > partition_from` 的分区。
- **`total_files / total_bytes / total_records`**：manifest 口径的期望值快照（含跨分区多版本），对账时的分母。

### 2.2 `file_task` — 系统的心脏

一行 = 一个 gz 文件 = 一个 checkpoint = 一个对账单元。断点续传、并行调度、幂等重放、对账四件事全部落在这张表上。表上并存三套机制，职责严格分离：

**① 身份（管存在性）**：唯一键 `uk_job_key (job_id, s3_key)`。回答"manifest 这条对应表里哪一行"，保证重跑 plan 不会让清单翻倍。身份对内容变化完全无感——同 key 重写的文件命中的是同一行。

**② 指纹（管有效性）**：`(file_bytes, manifest_records)` 两列。只在"行已存在且 DONE"时出场，回答"这个 DONE 还作不作数"：一致 → 盖章跳过；不一致（release 重排，同 key 内容被重写）→ 撤章，重置 PENDING 并更新指纹。指纹从不创建行、从不推进状态，只做这一个仲裁。`etag` 列是为"上游哪天提供真 etag"留的升级位。

**③ 状态机（管生命周期）**：`status / attempts / worker_id / lease_until`。PENDING→RUNNING→DONE 完全由 worker + 租约驱动（§3 伪 SQL A/B/C），planner 唯一触碰状态的时刻就是指纹撤章那一下。

Planner 的收敛逻辑就是三个分支——"刷新清单的同时保住已打的勾"：

```
for entry in manifest:
    row = 按 (job_id, s3_key) 查        ← 身份：有没有这一项
    if 无:          INSERT PENDING       ← 新文件入队
    elif 指纹一致:   不动                 ← DONE 保持 DONE，重跑免费
    else:           重置 PENDING + 更新指纹 ← 勾已失效，重做
```

**计数列与恒等式**。单文件、本次成功 attempt 口径：

```
records_read = upserted + stale + suppressed + filtered + deleted + failed
```

- 六个去向穷尽所有行的命运：送达 MySQL（NEW+APPLY）/ 版本判旧跳过 / 写抑制（版本新但投影 hash 同）/ 谓词过滤（全量）/ 出界删除（增量）/ 死信。
- **计数只在 DONE 时一次性写入**：计数是"这次成功 attempt 的结果"，不是进度条。边跑边累计的话，崩掉的 attempt 会留下半份账，恒等式永远配不平；一次性写让账目与状态跃迁同一原子动作生效——要么完整自洽的账可见，要么什么都没有。重跑的文件呈现 `read=stale` 是正常现象（口径=本 attempt 看到的世界）。
- **`affected_rows_sum`**：期望 `NEW + 2×APPLY`（MySQL 语义 insert=1 / update=2 / 守卫 no-op=0）。全 job 幂等重跑的断言是 `Σ=0`——这是"重放收敛"的可观测证据（验收 A2）。

**索引论证**（每个索引都对应一条高频查询，没有"以防万一"的索引）：

| 索引 | 服务的查询 |
|---|---|
| `uk_job_key (job_id, s3_key)` | planner 收敛的身份定位 |
| `idx_claim (job_id, status, file_bytes)` | 伪 SQL A 领任务：`WHERE job_id=? AND status='PENDING' ORDER BY file_bytes DESC` + SKIP LOCKED，索引同时覆盖过滤和排序 |
| `idx_lease (status, lease_until)` | 伪 SQL C 租约回收扫描：`WHERE status='RUNNING' AND lease_until < NOW()` |

**为什么没有子文件级 checkpoint**：文件是重试量子。细粒度断点（记行号）要解决"部分写入 + 断点本身的原子性"，复杂度高；而整文件重做的代价被两头压住——文件大小有上界（sources 均 ~3MB，works 分区文件也在百 MB 量级），且行级守卫让重做收敛为跳过。粗粒度 checkpoint 换简单的正确性论证，划算。

### 2.3 `sync_watermark` — 增量水位

每实体一行（PK=`entity`），语义是**承诺而非进度**："`max_partition_date` 之前的所有分区已被完整消化、通过对账"。

- **只在 job SUCCEEDED 的同一事务里推进**（伪 SQL D）。水位推进 = 对账全 PASS 的原子结论；任何中间状态都不动水位，所以增量 planner 可以无条件信任它。
- **为什么水位不取数据行的 `MAX(source_updated_at)`**：水位的粒度是**分区/文件**（同步轴），不是记录（内容轴）。数据列受跨分区多版本和处理乱序影响，`MAX()` 无法表达"某分区之前全部完成"；文件级任务表才是完成性的权威。
- **`filter_version` 在水位里**：水位语义绑定谓词版本。谓词升版后，旧水位对新谓词不成立（历史分区里有该补的行）→ 走 BACKFILL 重建，完成后水位换新版本号。
- `api_cursor / api_updated_through`：若未来叠加 API 日级增量的占位，切片不用。

### 2.4 `dead_letter` — 毒行隔离区

设计哲学：**单行毒不死文件，坏数据也要记账**。解析失败/投影失败/写入被拒的行进死信，task 照常 DONE，`records_failed` 计入恒等式——对账因此对"坏行"同样闭合（验收 A5：3 毒行 → `dead_letter=3`，恒等式含 `failed=3` 平衡）。

- **`stage` 三值 = 流水线阶段定位**：PARSE（JSON 断裂）/ TRANSFORM（缺 id、坏时间戳、投影失败）/ UPSERT（MySQL 拒绝）。排查时先看 stage 分布，能立刻区分"上游数据烂"和"我的代码烂"。
- **`payload_head` 4KB 截断 + `payload_ref` 外链**：控制面表不存大对象。截断头部够人工定位；真需要完整现场，外链到对象存储（联调不用）。
- **`error_type` 承担采样告警**：`schema_drift`（上游新字段，契约 C5）每 file×field 只记一条，防刷屏——死信表既是错误队列也是**变更探测器**。
- **`status` 人工闭环**：OPEN → REPLAYED / DISCARDED，配 `retry_count`。死信的出口是人，不是自动重试——能自动重试成功的就不该进死信。

### 2.5 `reconcile_check` — 对账结果

三层对账，**严格度递减、覆盖面递增**：

| check_type | 性质 | 断言 |
|---|---|---|
| FILE_ACCOUNTING | 精确断言 | 每文件恒等式成立且 `read == manifest_records` |
| YEAR_COUNT（works 期） | 精确断言 | 按发表年 MySQL count vs API `group_by` |
| TOTAL_COUNT | 容差告警 | `COUNT(*)` vs API `meta.count`，时差属预期，只告警不阻断 |

- `expected / actual / diff` 三列物化：`diff` 冗余可算但直接落列，报表和告警查询不用再算。
- `job_id` 可空：周期性全局对账（不挂在任何 job 上的巡检）复用同一张表。
- **与 C6 的咬合关系**：精确断言之所以敢用"=="而不是容差，前提是任务集合严格镜像 manifest——planner 但凡不收敛（半计划、重复行、陈旧 DONE），精确断言立刻误报。对账的严格性是买来的，价格就是计划层必须收敛；反过来，静默丢数/静默陈旧这两类最恶性的故障，正是靠这套精确断言才测得出来。

### 2.6 `es_index_registry` — 二期占位

ES 别名零停机切换的簿记：物理索引（`works_2020_v1`）↔ 读别名（`works_read`），BUILDING→ACTIVE→RETIRED 生命周期，`mapping_hash` 防 mapping 漂移，`filter_version` 记录索引对应的谓词版本。导入切片不触碰，建表保留是为了让控制面 schema 一次定型。

---

## 3. 四段伪 SQL：表设计的运行时另一半

表结构只回答"存什么"，这四段（写在 001 尾部注释、落进代码）回答"怎么动"：

- **A 领任务**：`FOR UPDATE SKIP LOCKED`——多 worker 并发领取互不阻塞、零自建协调（进程间唯一的协调点就是 MySQL 行锁）。`ORDER BY file_bytes DESC` 是 LPT 调度（最长处理时间优先），压掉大文件拖尾。`attempts+1` 在**领取时**而非失败时递增——崩溃的 attempt 没有机会执行"失败处理"，领取时计数才不漏。
- **B 心跳续租**：条件必须带 `worker_id`——防脑裂。被回收后复活的旧 worker，其续租 UPDATE 因 worker_id 不匹配而空转，不会抢回已重派的任务。
- **C 租约回收**：`RUNNING AND lease_until < NOW()` → 回 PENDING（attempts 未耗尽）或 FAILED（耗尽）。**这就是断点续传的全部秘密**——没有任何专门的"恢复流程"，崩溃 worker 的任务自然回队，被下一个 worker 自然领走。
- **D 水位推进**：job 置 SUCCEEDED 与水位 UPDATE 同一事务——"批次成功"和"水位前移"是一个事实的两面，不允许出现只发生一半的窗口。

---

## 4. 数据面：`openalex.sources`

### 4.1 设计方法：按消费者设计

这张表没有终端用户。消费者只有四个：CDC（二期，读 binlog 行镜像）、全量扫描（ES 建索引，按 PK 分片）、对账（COUNT/指纹聚合）、parquet 导出。每一列的存在、每一个索引的取舍，都对着这四个消费者论证——没有消费者的列不设，没有查询的索引不建。

### 4.2 四层列分类

| 层 | 列 | 职责 |
|---|---|---|
| 控制列 | `source_updated_at` / `content_hash` / `first_imported_at` / `row_updated_at` | 幂等与观测 |
| 谓词列 | （sources 无；works = `publication_year`） | 内容轴的过滤依据 |
| 类型化业务列 | `display_name` / `source_type` / `issn_l` / `country_code` / `is_oa` / `is_in_doaj` / `works_count` / `cited_by_count` | 值得类型和列存在的字段，克制 |
| JSON 载荷列 | `payload`（白名单其余 24 字段） | 不透明透传，ES 文档素材 |

分层的意义：**每层的变更成本不同**。控制列动 = 幂等协议变（大事）；谓词列动 = filter_version 升版（BACKFILL）；类型化列动 = DDL + 回填；payload 内容动 = 无感（上游加字段进 payload 白名单即可）。把变更频率最高的部分放进变更成本最低的层。

### 4.3 控制列逐列解读

- **`source_updated_at DATETIME(3)`**——行级幂等的**有序版本判据**（0.3）。语义是"最近一次**引起投影变化**的上游时间"：写抑制（SUPPRESS）时不推进，所以它不是"上游最新时间"而是"本行内容对应的上游时间"。DATETIME 而非 TIMESTAMP：全链按 UTC 理解、不做时区换算，且无 2038 上限；(3) 毫秒精度与上游 `updated_date` 的毫秒位对齐。它是 SQL 守卫唯一引用的列，且在 `ON DUPLICATE KEY UPDATE` 中**必须最后赋值**（赋值从左到右生效，先赋会污染后续条件——契约 C3 的赋值顺序陷阱）。
- **`content_hash BIGINT UNSIGNED`**——写抑制判据：版本推进但投影不变的上游更新直接丢弃，保护下游整条链（binlog/CDC/ES）不被无效流量冲刷。**只由导入器从上游记录计算，永不从本表 payload 回读重算**——MySQL JSON 类型会重排键序，回读重算=永远不相等。hash 判断在客户端预读层而不进 SQL 条件（进条件会与自身赋值互锁，C3）。
- **`first_imported_at` / `row_updated_at`**——纯观测列，不参与任何判断。前者记首次落库，后者记最近实际写入（写抑制生效时不动，因为根本没发 UPDATE）。它们回答运维问题："这行什么时候来的、最近一次真实变更是什么时候"。

### 4.4 主键与索引

- **PK = BIGINT 短号**（`https://openalex.org/S15574646` → `15574646`）：ID 是契约（C1），全 ID 可无损重建。比字符串全 ID 省 ~4× 主键体积——InnoDB 聚簇索引下主键体积会被每个二级索引再复制一遍，works 期 1 亿行时这是实打实的空间和缓存差异。
- **起步零二级索引**：导入期每个二级索引都是写放大税，而四个消费者的访问路径全部走 PK（CDC 按行、扫描按 PK 分片、对账全表聚合、导出全表）。索引在真实查询出现后按查询论证补——"以防万一"的索引在这张表上只有成本没有收益。
- `ROW_FORMAT=DYNAMIC`：肥 payload（均 23KB）走溢出页，行内存指针，B+ 树保持紧凑。

### 4.5 works 期的延伸（预告，契约另立）

- 谓词列 `publication_year` 落进第四层分类预留的空位；增量中谓词不匹配 → 出界删除，`records_deleted` 计数位已在 `file_task` 预留。
- 不做 MySQL 分区表：分区键必须进主键，`(id, publication_year)` 复合主键会破坏 id 全局唯一语义（同 id 换年份=两行），得不偿失。
- `payload` 载荷内部裁剪（works 的 `abstract_inverted_index` 等大头）是 works 切片的课题，sources v1 不做。

---

## 5. 取舍速查（高频追问一句话版）

| 追问 | 一句话回答 |
|---|---|
| 为什么控制面用 MySQL 不用 Redis/ZK？ | 需要的是事务 + 行锁 + SKIP LOCKED + 可 SQL 对账，单机 MySQL 全有，且状态天然持久 |
| 为什么不防止重复执行而是容忍？ | 防止需要分布式锁+完成标记，那套机制自身又需要幂等，循环论证；容忍只花一个唯一键 |
| 指纹为什么不用文件哈希？ | manifest 不提供 etag；(length, record_count) 零成本、判别力够，碰撞由追加式快照+对账兜底 |
| 为什么文件级不做行号断点？ | 文件是重试量子：大小有上界，重做被行级守卫兜成收敛，粗 checkpoint 换简单正确性 |
| 计数为什么不边跑边累计？ | 计数是成功 attempt 的结果不是进度条，一次性写保证账目与状态原子一致 |
| 水位为什么不用 MAX(source_updated_at)？ | 水位在同步轴（分区/文件粒度），数据列在内容轴且受多版本乱序污染，两轴不混 |
| 为什么 hash 不进 SQL 守卫条件？ | ON DUPLICATE KEY UPDATE 赋值从左到右生效，hash 进条件与自身赋值互锁；上移客户端还赚到精确计数 |
| 为什么 sources 表零二级索引？ | 四个消费者全走 PK；导入期二级索引纯写放大，索引按真实查询论证后再加 |
| 谓词变了怎么办？ | filter_version 升版 → 新 BACKFILL job 回补/删除，水位随版本重建；不在旧 job 上修补 |
| 上游删除怎么处理？ | 免费层无删除信号（已查明）：works 靠谓词出界删除，全集靠周期 reconcile 对账兜底 |
