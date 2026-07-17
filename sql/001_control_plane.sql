-- ============================================================
-- OpenAlex 同步系统 · 控制面 Schema (MySQL 8.x)
-- 设计原则:
--   1. 控制面(openalex_sync)与数据面(openalex 库)分库隔离:
--      CDC(二期)只订阅数据面, 控制面高频状态写不进 CDC 视野
--      [修订 2026-07-17: 原"数据面不过 MySQL"随 MySQL 权威源定案作废]
--   2. 一切围绕: 幂等重放 + 断点续传 + 可对账
--   3. 文件 = 最小任务单元 = 最小重放单元 = 对账单元
-- ============================================================

CREATE DATABASE IF NOT EXISTS openalex_sync
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE openalex_sync;

-- ------------------------------------------------------------
-- 1) sync_job: 同步批次。一次全量 / 一次月度增量 / 一次谓词扩容回填 = 一行
-- ------------------------------------------------------------
CREATE TABLE sync_job (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  entity         VARCHAR(32)  NOT NULL COMMENT 'works / authors / sources ...',
  job_type       ENUM('FULL','INCREMENTAL','BACKFILL') NOT NULL,
  snapshot_date  DATE NOT NULL COMMENT '本次消费的快照版本 (manifest.date)',
  filter_version VARCHAR(64) NOT NULL DEFAULT 'none.v1'
                 COMMENT '子集谓词版本(维表 none.v1 / works pubyear_ge_2019.v1), 谓词变更必须升版本',
  status         ENUM('CREATED','RUNNING','SUCCEEDED','FAILED','CANCELLED')
                 NOT NULL DEFAULT 'CREATED',
  partition_from DATE NULL COMMENT '增量: 只处理 updated_date > partition_from 的分区',
  partition_to   DATE NULL COMMENT '本次覆盖到的最大分区',
  total_files    INT UNSIGNED NOT NULL DEFAULT 0,
  total_bytes    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  total_records  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'manifest 口径(含跨分区重复版本)',
  started_at     DATETIME NULL,
  finished_at    DATETIME NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_entity_snap_type (entity, snapshot_date, job_type, filter_version)
) ENGINE=InnoDB COMMENT='同步批次';

-- ------------------------------------------------------------
-- 2) file_task: 文件级任务, 整个系统的心脏
--    断点续传/并行调度/幂等重放/对账 全部落在这一张表
-- ------------------------------------------------------------
CREATE TABLE file_task (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_id           BIGINT UNSIGNED NOT NULL,
  entity           VARCHAR(32)  NOT NULL,
  s3_key           VARCHAR(512) NOT NULL COMMENT 'data/jsonl/works/updated_date=.../part_x.gz',
  partition_date   DATE NOT NULL COMMENT 'updated_date 分区',
  etag             VARCHAR(64) NULL COMMENT '源文件 ETag, 变化说明快照重排需重跑',
  file_bytes       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  manifest_records BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'manifest 声明行数, 对账基准',
  status           ENUM('PENDING','RUNNING','DONE','FAILED','SKIPPED')
                   NOT NULL DEFAULT 'PENDING',
  attempts         TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts     TINYINT UNSIGNED NOT NULL DEFAULT 3,
  worker_id        VARCHAR(64) NULL COMMENT '租约持有者',
  lease_until      DATETIME NULL COMMENT '租约到期仍未完成 → 调度器回收重派',
  -- 恒等式(单文件, 本次 attempt 口径):
  --   read = upserted + stale + suppressed + filtered + deleted + failed
  records_read      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  records_upserted  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '送达 MySQL 的 NEW+APPLY 行(契约 C3)',
  records_stale     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '预读判旧(版本不更新), 正常跳过',
  records_suppressed BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '写抑制: 版本更新但投影 hash 相同',
  records_filtered  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '谓词不匹配(全量跳过)',
  records_deleted   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '增量中谓词不匹配→出界删除',
  records_failed    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '进 dead_letter',
  affected_rows_sum BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Σ affected_rows, 期望 NEW+2×APPLY; 幂等重跑全 job 断言=0',
  error_msg        VARCHAR(1024) NULL,
  started_at       DATETIME NULL,
  finished_at      DATETIME NULL,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_job_key (job_id, s3_key),
  KEY idx_claim (job_id, status, file_bytes),
  KEY idx_lease (status, lease_until),
  CONSTRAINT fk_task_job FOREIGN KEY (job_id) REFERENCES sync_job(id)
) ENGINE=InnoDB COMMENT='文件级任务/checkpoint';

-- ------------------------------------------------------------
-- 3) sync_watermark: 每实体的增量水位
-- ------------------------------------------------------------
CREATE TABLE sync_watermark (
  entity             VARCHAR(32) PRIMARY KEY,
  filter_version     VARCHAR(64) NOT NULL,
  snapshot_date      DATE NOT NULL COMMENT '已完整消化到的快照版本',
  max_partition_date DATE NOT NULL COMMENT '已处理到的最大 updated_date 分区',
  api_cursor         VARCHAR(256) NULL COMMENT '若叠加 API 日级增量: 游标',
  api_updated_through DATETIME NULL COMMENT 'API 增量已覆盖到的时间点',
  updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='增量水位, 只在 job SUCCEEDED 后原子推进';

-- ------------------------------------------------------------
-- 4) dead_letter: 坏记录隔离区, 支持人工排查后重放
-- ------------------------------------------------------------
CREATE TABLE dead_letter (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  task_id      BIGINT UNSIGNED NOT NULL,
  entity       VARCHAR(32) NOT NULL,
  openalex_id  VARCHAR(64) NULL COMMENT '解析失败时可能拿不到',
  s3_key       VARCHAR(512) NOT NULL,
  line_no      INT UNSIGNED NULL,
  stage        ENUM('PARSE','TRANSFORM','UPSERT') NOT NULL,
  error_type   VARCHAR(128) NOT NULL COMMENT 'json_decode / schema_drift / mysql_rejected ...',
  error_msg    VARCHAR(2048) NULL,
  payload_head VARCHAR(4096) NULL COMMENT '原始记录截断头部(前 4KB)',
  payload_ref  VARCHAR(512) NULL COMMENT '完整 payload 在对象存储的 key(可选, 联调阶段不用)',
  status       ENUM('OPEN','REPLAYED','DISCARDED') NOT NULL DEFAULT 'OPEN',
  retry_count  TINYINT UNSIGNED NOT NULL DEFAULT 0,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_status (status, entity),
  KEY idx_task (task_id)
) ENGINE=InnoDB COMMENT='死信队列';

-- ------------------------------------------------------------
-- 5) reconcile_check: 对账结果, 三个层次
--    FILE_ACCOUNTING: 文件恒等式 read = upserted+stale+suppressed+filtered+deleted+failed
--    YEAR_COUNT:      (works 期)按发表年 MySQL count vs API group_by 精确计数
--    TOTAL_COUNT:     实体总数 MySQL count(*) vs API meta.count (容差告警, 非精确断言)
-- ------------------------------------------------------------
CREATE TABLE reconcile_check (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_id     BIGINT UNSIGNED NULL COMMENT '可为空: 周期性全局对账不挂在 job 上',
  entity     VARCHAR(32) NOT NULL,
  check_type ENUM('FILE_ACCOUNTING','YEAR_COUNT','TOTAL_COUNT') NOT NULL,
  dimension  VARCHAR(64) NOT NULL COMMENT '文件key哈希 / 年份 / total',
  expected   BIGINT NOT NULL,
  actual     BIGINT NOT NULL,
  diff       BIGINT NOT NULL,
  status     ENUM('PASS','FAIL') NOT NULL,
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_status (entity, check_type, status, checked_at)
) ENGINE=InnoDB COMMENT='对账结果';

-- ------------------------------------------------------------
-- 6) es_index_registry: ES 索引版本登记, 零停机切换的簿记
--    [二期 CDC→ES 使用; 导入切片不触碰, 建表保留]
-- ------------------------------------------------------------
CREATE TABLE es_index_registry (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  entity         VARCHAR(32) NOT NULL,
  index_name     VARCHAR(128) NOT NULL COMMENT '物理索引: works_2020_v1',
  alias_name     VARCHAR(128) NOT NULL COMMENT '读别名: works_read',
  filter_version VARCHAR(64) NOT NULL,
  mapping_hash   VARCHAR(64) NOT NULL COMMENT 'mapping JSON 的 sha256, 防漂移',
  status         ENUM('BUILDING','ACTIVE','RETIRED') NOT NULL DEFAULT 'BUILDING',
  doc_count      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  activated_at   DATETIME NULL,
  UNIQUE KEY uk_index (index_name)
) ENGINE=InnoDB COMMENT='索引版本与别名切换记录';

-- ============================================================
-- 核心操作模式 (worker 侧伪 SQL, 写进代码里)
-- ============================================================

-- A. 领任务: MySQL 8 的 SKIP LOCKED, 多 worker 并发领取互不阻塞
--    ORDER BY file_bytes DESC = 大文件优先(LPT 调度), 压掉长尾
--
--   START TRANSACTION;
--   SELECT id, s3_key FROM file_task
--    WHERE job_id = ? AND status = 'PENDING'
--    ORDER BY file_bytes DESC LIMIT 1
--    FOR UPDATE SKIP LOCKED;
--   UPDATE file_task
--      SET status='RUNNING', worker_id=?, attempts=attempts+1,
--          lease_until = NOW() + INTERVAL 15 MINUTE,
--          started_at = COALESCE(started_at, NOW())
--    WHERE id = ?;
--   COMMIT;

-- B. 心跳续租 (worker 每 5 分钟, 必须带 worker_id 防脑裂):
--   UPDATE file_task SET lease_until = NOW() + INTERVAL 15 MINUTE
--    WHERE id = ? AND worker_id = ? AND status = 'RUNNING';

-- C. 租约回收 (调度器每分钟): 崩溃 worker 的任务自动回队 —— 断点续传的全部秘密
--   UPDATE file_task SET status='PENDING', worker_id=NULL
--    WHERE status='RUNNING' AND lease_until < NOW() AND attempts < max_attempts;
--   UPDATE file_task SET status='FAILED', error_msg='attempts exhausted'
--    WHERE status='RUNNING' AND lease_until < NOW() AND attempts >= max_attempts;

-- D. 水位推进 (仅当 job 全部 file_task = DONE, 事务内):
--   UPDATE sync_job SET status='SUCCEEDED', finished_at=NOW() WHERE id=?;
--   UPDATE sync_watermark SET snapshot_date=?, max_partition_date=? WHERE entity=?;
