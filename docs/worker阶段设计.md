需求：

同步文件  根据entity

支持多线程

崩溃的发现 

崩溃的处理

---

场景：

崩溃场景：

worker进程进行到一半崩溃了

文件的断点续传，文件间的断点续传

异常场景：

远端故障：重试异常

Fatal异常，程序本身异常

Poisoned异常，确定是数据本身的问题

---

设计：

契约拆分：

1. 根据entity领取任务(线程池)
2. 状态变更
3. 读取数据库(同时读取那些僵尸数据)
4. Reader
   1. 读取文件
   2. 解压文件
   3. 解析文件

5. commitChunk
   1. 校验数据
   2. 批量插入数据库(参考Spring Batch)
      1. 事务的处理
      2. 事务的拆分

6. 状态变更

异常情况：

1. 流式解析正常，但是read_count<record_count说明远端发生了变化，这时候重正常变done状态，并进行记录，后续校验交给reconcile
2. 流式解析异常 read_count<record_count 根据情况记录dead row或者进行重试或者直接报错

异常设计：

1. 可重试异常    Retryable
2. 数据本身异常  Poisoned
3. 程序异常   Fatal

异常处理:

参考SpringBatch的策略 

```
1   |  REPEAT(until=exhausted, exception=not critical) {
|
2   |    TX {
3   |      REPEAT(size=5) {
|
4   |        RETRY(stateful, exception=deadlock loser) {
4.1 |          input;
5   |        } PROCESS {
5.1 |          output;
6   |        } SKIP and RECOVER {
|          notify;
|        }
|
|      }
|    }
|
|  }
```

Task状态机设计：

1. Pending
2. Running
3. Done
4. Failed

崩溃情况的发现：

1. 不发现，如果在事务执行过程中出现问题由数据库自己回滚
2. 超时自动视为崩溃
3. 建立心跳机制，每写入一个批次更新一下last_heartbeat
4. 根据最后一次心跳时间和now的时间的差值判断是否超时 

崩溃情况的处理：

1. 让数据库自己回滚
2. 重新同步
3. 通过幂等性保证只插入一次

性能瓶颈分析：

数据库的写操作

优化：批量顺序写+关闭binlog+多线程+减少索引

---

实现：