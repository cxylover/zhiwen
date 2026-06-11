# 用户关系模块深度解析 — Outbox + Canal + Kafka 事件驱动架构

> 一主多从 + 事件驱动模型：关注表为主，粉丝表和计数系统为从，通过 Outbox + Canal + Kafka 异步驱动从表更新。

---

## 一、架构总览

### 1.1 核心设计思想

```
传统做法（同步写多表）：
  用户A关注用户B → 同一事务写 following 表 + follower 表 + 更新计数
  问题：事务粒度大、锁竞争激烈、扩展性差

本项目做法（一主多从 + 事件驱动）：
  用户A关注用户B → 同一事务只写 following 表 + outbox 表
                 → Canal 监听 binlog → Kafka → 异步更新 follower 表 + 计数
  优势：写事务小、从表异步解耦、可独立扩展
```

### 1.2 架构图

```
                         ┌──────────────────────────────────────────┐
                         │           MySQL (同一事务)                │
                         │                                          │
 用户请求 ──→ AuthService ──→  INSERT following (主表)              │
                         │    INSERT outbox   (事件表)              │
                         │           ↑                              │
                         └───────────┼──────────────────────────────┘
                                     │ binlog
                                     ▼
                              ┌──────────────┐
                              │    Canal      │  订阅 outbox 表的 binlog
                              │  (binlog监听) │  提取 payload 字段
                              └──────┬───────┘
                                     │ 写入 Kafka
                                     ▼
                              ┌──────────────┐
                              │    Kafka      │  topic: canal-outbox
                              │  (消息队列)   │
                              └──────┬───────┘
                                     │ 消费
                                     ▼
                         ┌──────────────────────────────┐
                         │    CanalOutboxConsumer        │
                         │  解析 payload → RelationEvent │
                         └──────────┬───────────────────┘
                                    │
                                    ▼
                         ┌──────────────────────────────┐
                         │    RelationEventProcessor     │
                         │                               │
                         │  ① 去重 (Redis dedup key)     │
                         │  ② 写 follower 表 (从表)      │
                         │  ③ 更新 Redis ZSet 缓存       │
                         │  ④ 更新 SDS 计数              │
                         └──────────────────────────────┘
```

### 1.3 模块文件结构

```
com.tongji.relation/
├── api/
│   └── RelationController.java         # HTTP 入口：关注/取消/查询
├── service/
│   ├── RelationService.java            # 接口
│   └── impl/
│       └── RelationServiceImpl.java    # 核心实现：关注写主表+outbox，读缓存
├── mapper/
│   └── RelationMapper.java             # MyBatis Mapper 接口
├── event/
│   └── RelationEvent.java              # 事件 record (type, fromUserId, toUserId, id)
├── outbox/
│   ├── OutboxMapper.java               # Outbox 表写入
│   ├── OutboxTopics.java               # Kafka topic 常量
│   ├── CanalKafkaBridge.java           # Canal → Kafka 桥接器
│   └── CanalOutboxConsumer.java        # Kafka 消费者
└── processor/
    └── RelationEventProcessor.java     # 事件处理：写从表+更新缓存+更新计数

com.tongji.counter/
├── service/
│   ├── UserCounterService.java         # 用户计数接口
│   └── impl/
│       └── UserCounterServiceImpl.java # SDS 二进制计数实现
└── schema/
    └── UserCounterKeys.java            # Redis key 生成

resources/mapper/
├── RelationMapper.xml                  # following/follower 表 SQL
└── OutboxMapper.xml                    # outbox 表 SQL

db/schema.sql                           # 三张表 DDL
```

---

## 二、数据库表结构

### 2.1 following 表（主表）

```sql
CREATE TABLE following (
    id          BIGINT UNSIGNED NOT NULL,        -- Snowflake ID
    from_user_id BIGINT UNSIGNED NOT NULL,       -- 关注发起者
    to_user_id  BIGINT UNSIGNED NOT NULL,        -- 被关注者
    rel_status  TINYINT NOT NULL DEFAULT 1,      -- 1=有效, 0=取消
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_from_to (from_user_id, to_user_id),   -- 防重复关注
    KEY idx_from_created (from_user_id, created_at, to_user_id, rel_status),  -- 关注列表查询
    KEY idx_to (to_user_id, from_user_id, rel_status)   -- 反向查询
);
```

### 2.2 follower 表（从表）

```sql
CREATE TABLE follower (
    id          BIGINT UNSIGNED NOT NULL,
    to_user_id  BIGINT UNSIGNED NOT NULL,        -- 被关注者
    from_user_id BIGINT UNSIGNED NOT NULL,       -- 关注发起者（粉丝）
    rel_status  TINYINT NOT NULL DEFAULT 1,
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_to_from (to_user_id, from_user_id),   -- 防重复
    KEY idx_to_created (to_user_id, created_at, from_user_id, rel_status),  -- 粉丝列表查询
    KEY idx_from (from_user_id, to_user_id, rel_status)
);
```

### 2.3 outbox 表（事件表）

```sql
CREATE TABLE outbox (
    id             BIGINT UNSIGNED NOT NULL,      -- 事件 ID
    aggregate_type VARCHAR(64) NOT NULL,           -- "following"
    aggregate_id   BIGINT UNSIGNED NULL,           -- following 表的记录 ID
    type           VARCHAR(64) NOT NULL,           -- "FollowCreated" / "FollowCanceled"
    payload        JSON NOT NULL,                  -- 事件 JSON
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_outbox_agg (aggregate_type, aggregate_id),
    KEY ix_outbox_ct (created_at)
);
```

### 2.4 三张表的关系

```
following 表（主）        outbox 表（事件）         follower 表（从）
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│ A 关注 B        │ ──→  │ FollowCreated   │ ──→  │ B 的粉丝里有 A  │
│ (同一事务写入)   │      │ (同一事务写入)   │      │ (异步写入)       │
└─────────────────┘      └─────────────────┘      └─────────────────┘
```

---

## 三、核心流程逐行分析

### 3.1 关注流程 — `RelationServiceImpl.follow()`

```java
@Transactional
public boolean follow(long fromUserId, long toUserId)
```

**第1步：Lua 令牌桶限流**

```java
Long ok = redis.execute(tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
if (ok == 0L) return false;
```

- Redis key: `rl:follow:{fromUserId}`
- 容量 100 令牌，每秒恢复 1 个
- 令牌不足 → 返回 false（限流）

**第2步：写入 following 表（主表）**

```java
long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);  // Snowflake ID
int inserted = mapper.insertFollowing(id, fromUserId, toUserId, 1);
```

SQL:
```sql
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
VALUES (?, ?, ?, 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE rel_status=VALUES(rel_status), updated_at=VALUES(updated_at)
```

- `ON DUPLICATE KEY` 处理重复关注（软删除后重新关注）

**第3步：写入 outbox 表（事件表）— 同一事务**

```java
if (inserted > 0) {
    Long outId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    String payload = objectMapper.writeValueAsString(
        new RelationEvent("FollowCreated", fromUserId, toUserId, id)
    );
    outboxMapper.insert(outId, "following", id, "FollowCreated", payload);
}
```

SQL:
```sql
INSERT INTO outbox (id, aggregate_type, aggregate_id, type, payload, created_at)
VALUES (?, 'following', ?, 'FollowCreated', ?, NOW(3))
```

payload JSON:
```json
{
  "type": "FollowCreated",
  "fromUserId": 1001,
  "toUserId": 2002,
  "id": 312045628760920064
}
```

**关键：following 和 outbox 在同一个 `@Transactional` 中，要么都成功，要么都失败。**

### 3.2 取消关注流程 — `RelationServiceImpl.unfollow()`

```java
@Transactional
public boolean unfollow(long fromUserId, long toUserId)
```

**第1步：逻辑删除 following 表**

```java
int updated = mapper.cancelFollowing(fromUserId, toUserId);
```

SQL:
```sql
UPDATE following SET rel_status=0, updated_at=NOW(3)
WHERE from_user_id=? AND to_user_id=?
```

**第2步：写入 outbox — 同一事务**

```java
if (updated > 0) {
    String payload = objectMapper.writeValueAsString(
        new RelationEvent("FollowCanceled", fromUserId, toUserId, null)
    );
    outboxMapper.insert(outId, "following", null, "FollowCanceled", payload);
}
```

注意：`aggregateId` 为 `null`（因为取消关注时不需要 following 记录 ID）。

### 3.3 Canal 桥接 — `CanalKafkaBridge`

```java
@Service
public class CanalKafkaBridge implements SmartLifecycle
```

**启动流程 (`start()`):**

1. 创建 Canal 连接器，连接 Canal Server (`host:port`)
2. 订阅过滤表达式 (`filter`)，只监听 outbox 表
3. 回滚到上次确认位点

**主循环：**

```
while (running) {
    connector.getWithoutAck(batchSize)      ← 拉取一批 binlog 事件
        │
        ├── 空批次 → sleep(intervalMs) → 继续轮询
        │
        └── 遍历 entries:
            │
            ├── 只处理 ROWDATA 类型
            │
            ├── 解析 RowChange (protobuf)
            │
            ├── 只处理 INSERT 和 UPDATE 事件
            │
            ├── 遍历 rowData.getAfterColumnsList():
            │   └── 提取 "payload" 字段的值
            │
            ├── 构建 JSON: { table, type, data: [{ payload: "..." }] }
            │
            └── kafka.send("canal-outbox", json)  ← 发送到 Kafka
}
connector.ack(batchId)   ← 批次确认位点
```

**为什么只提取 payload 字段？**

因为 outbox 表的设计就是：Canal 只需要把 payload（事件 JSON）转发到 Kafka，其他字段（id、aggregate_type 等）是给 Canal 识别用的，下游不需要。

### 3.4 Kafka 消费 — `CanalOutboxConsumer`

```java
@KafkaListener(topics = "canal-outbox", groupId = "relation-outbox-consumer")
public void onMessage(String message, Acknowledgment ack)
```

**处理流程：**

1. `OutboxMessageUtil.extractRows(objectMapper, message)` — 从 Canal 消息中提取行数据
2. 遍历每行，提取 `payload` 字段
3. 反序列化为 `RelationEvent` 对象
4. 调用 `processor.process(evt)` 处理事件
5. `ack.acknowledge()` — 手动确认位点

### 3.5 事件处理 — `RelationEventProcessor.process()`

```java
public void process(RelationEvent evt)
```

**第1步：Redis 去重（幂等保证）**

```java
String dk = "dedup:rel:" + evt.type() + ":" + evt.fromUserId() + ":" + evt.toUserId() + ":" + evt.id();
Boolean first = redis.opsForValue().setIfAbsent(dk, "1", Duration.ofMinutes(10));
if (first == null || !first) return;  // 已处理过，跳过
```

- Redis key: `dedup:rel:FollowCreated:1001:2002:312045628760920064`
- TTL: 10 分钟
- `setIfAbsent` = SETNX，只有第一次设置成功返回 true

**第2步：FollowCreated 事件处理**

```java
if ("FollowCreated".equals(evt.type())) {
    // 2a. 写入 follower 表（从表）
    mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);

    // 2b. 更新 Redis ZSet 缓存
    long now = System.currentTimeMillis();
    redis.opsForZSet().add("uf:flws:" + fromUserId, String.valueOf(toUserId), now);  // 关注列表
    redis.opsForZSet().add("uf:fans:" + toUserId, String.valueOf(fromUserId), now);  // 粉丝列表
    redis.expire("uf:flws:" + fromUserId, Duration.ofHours(2));  // TTL 2小时
    redis.expire("uf:fans:" + toUserId, Duration.ofHours(2));

    // 2c. 更新 SDS 计数
    userCounterService.incrementFollowings(fromUserId, 1);   // 关注数 +1
    userCounterService.incrementFollowers(toUserId, 1);      // 粉丝数 +1
}
```

**第3步：FollowCanceled 事件处理**

```java
else if ("FollowCanceled".equals(evt.type())) {
    // 3a. 逻辑删除 follower 表
    mapper.cancelFollower(toUserId, fromUserId);

    // 3b. 移除 ZSet 缓存
    redis.opsForZSet().remove("uf:flws:" + fromUserId, String.valueOf(toUserId));
    redis.opsForZSet().remove("uf:fans:" + toUserId, String.valueOf(fromUserId));
    redis.expire("uf:flws:" + fromUserId, Duration.ofHours(2));
    redis.expire("uf:fans:" + toUserId, Duration.ofHours(2));

    // 3c. 更新 SDS 计数
    userCounterService.incrementFollowings(fromUserId, -1);  // 关注数 -1
    userCounterService.incrementFollowers(toUserId, -1);     // 粉丝数 -1
}
```

---

## 四、SDS 计数系统

### 4.1 什么是 SDS？

SDS (Structured Data String) 是一种紧凑的二进制结构，把 5 个计数器打包成 20 字节存入一个 Redis STRING：

```
Redis key: ucnt:{userId}
Value: 20 字节二进制 (5 × 4字节，大端序 32 位整型)

字节布局：
┌──────────┬──────────┬──────────┬──────────┬──────────┐
│ 关注数    │ 粉丝数    │ 发文数    │ 获赞数    │ 获藏数    │
│ [0:4]    │ [4:8]    │ [8:12]   │ [12:16]  │ [16:20]  │
│ 4字节BE  │ 4字节BE  │ 4字节BE  │ 4字节BE  │ 4字节BE  │
└──────────┴──────────┴──────────┴──────────┴──────────┘
```

### 4.2 为什么用 SDS 而不是 5 个独立 key？

```
5 个独立 key：
  GET ucnt:1001:followings     ← 1 次网络往返
  GET ucnt:1001:followers      ← 1 次网络往返
  GET ucnt:1001:posts          ← 1 次网络往返
  GET ucnt:1001:likedPosts     ← 1 次网络往返
  GET ucnt:1001:favedPosts     ← 1 次网络往返
  共 5 次网络往返

SDS：
  GET ucnt:1001                ← 1 次网络往返，拿到 20 字节，本地解析
  共 1 次网络往返
```

### 4.3 原子增量 — Lua 脚本

```lua
-- INCR_FIELD_LUA
local cntKey = KEYS[1]        -- "ucnt:1001"
local schemaLen = ARGV[1]     -- 5 (段数)
local fieldSize = ARGV[2]     -- 4 (每段字节数)
local idx = ARGV[3]           -- 段索引 (1基，1=关注, 2=粉丝, ...)
local delta = ARGV[4]         -- 增量 (+1 或 -1)

-- 读取当前值
local cnt = redis.call('GET', cntKey)
if not then cnt = string.rep(string.char(0), 20) end  -- 初始化为全0

-- 读取目标段的值
local off = (idx - 1) * 4
local v = read32be(cnt, off) + delta
if v < 0 then v = 0 end  -- 不允许负数

-- 写回目标段
cnt = cnt[1:off] .. write32be(v) .. cnt[off+4+1:]
redis.call('SET', cntKey, cnt)
return 1
```

**调用示例：**

```java
// 关注数 +1
redis.execute(incrScript, List.of("ucnt:1001"), "5", "4", "1", "1");
//                                  key        段数 段大小 索引 增量

// 粉丝数 -1
redis.execute(incrScript, List.of("ucnt:2002"), "5", "4", "2", "-1");
```

### 4.4 采样校验与重建

`RelationController.counter()` 每次读取计数时，会做采样校验：

```
读取 SDS → 解析关注数、粉丝数
  │
  ├── Redis 锁限流：ucnt:chk:{userId}，300 秒最多触发一次
  │
  ├── 对比 DB：
  │   dbFollowings = relationMapper.countFollowingActive(userId)
  │   dbFollowers  = relationMapper.countFollowerActive(userId)
  │
  ├── 不一致 → rebuildAllCounters(userId)
  │   ├── 从 DB 读取关注数、粉丝数
  │   ├── 从 DB 读取发文列表，聚合获赞、获藏总数
  │   └── 回写 SDS
  │
  └── 一致 → 直接返回 SDS 值
```

---

## 五、读路径 — 缓存架构

### 5.1 三级缓存

```
L1: Caffeine 本地缓存 (大V用户)
    ├── flwsTopCache — 关注列表前 500
    ├── fansTopCache — 粉丝列表前 500
    ├── 最大 1000 个用户
    └── TTL 10 分钟

L2: Redis ZSet
    ├── uf:flws:{userId} — 关注列表，score=时间戳
    ├── uf:fans:{userId} — 粉丝列表，score=时间戳
    └── TTL 2 小时

L3: MySQL
    ├── following 表 — 关注列表
    └── follower 表 — 粉丝列表
```

### 5.2 读取流程 (`getListWithOffset`)

```
请求：获取用户 1001 的关注列表 (offset=0, limit=20)
  │
  ├── L1: flwsTopCache.getIfPresent(1001)
  │   ├── 命中且 offset < top.size() → 直接返回
  │   └── 未命中或 offset 超范围 → 继续
  │
  ├── L2: Redis ZREVRANGE uf:flws:1001 0 19
  │   ├── 命中 → 返回
  │   └── 未命中 → 继续
  │
  └── L3: MySQL SELECT to_user_id FROM following
          WHERE from_user_id=1001 AND rel_status=1
          ORDER BY created_at DESC LIMIT 20 OFFSET 0
      │
      ├── 回填 ZSet (score=createdAt 毫秒时间戳)
      ├── 设置 TTL 2 小时
      ├── 如果是大V (粉丝≥50万) → 更新 Caffeine 本地缓存
      └── 从 ZSet 重新读取返回
```

### 5.3 大V检测

```java
private boolean isBigV(long userId) {
    byte[] raw = redis.get("ucnt:" + userId);  // 读 SDS
    long followers = read32be(raw, 4);           // 第2段 = 粉丝数
    return followers >= 500_000L;                // 50万以上 = 大V
}
```

大V用户的关注/粉丝列表会额外缓存到 Caffeine 本地缓存（前 500 名），避免每次请求都穿透到 Redis。

---

## 六、完整时序图

### 6.1 关注流程

```
前端                RelationController     RelationServiceImpl      MySQL
 │                       │                       │                    │
 │ POST /follow          │                       │                    │
 │ toUserId=2002         │                       │                    │
 │──────────────────────>│                       │                    │
 │                       │ follow(1001, 2002)    │                    │
 │                       │──────────────────────>│                    │
 │                       │                       │                    │
 │                       │                       │ ① Lua 令牌桶限流    │
 │                       │                       │   Redis 执行        │
 │                       │                       │                    │
 │                       │                       │ ② INSERT following  │
 │                       │                       │───────────────────>│
 │                       │                       │                    │
 │                       │                       │ ③ INSERT outbox     │
 │                       │                       │───────────────────>│
 │                       │                       │  (同一事务 commit)   │
 │                       │                       │<───────────────────│
 │                       │                       │                    │
 │                       │  true                 │                    │
 │                       │<──────────────────────│                    │
 │  true                 │                       │                    │
 │<──────────────────────│                       │                    │
```

### 6.2 异步事件处理

```
MySQL binlog           Canal              Kafka              CanalOutboxConsumer    RelationEventProcessor
 │                       │                  │                       │                      │
 │ INSERT outbox         │                  │                       │                      │
 │ binlog 事件           │                  │                       │                      │
 │──────────────────────>│                  │                       │                      │
 │                       │ 提取 payload     │                       │                      │
 │                       │─────────────────>│                       │                      │
 │                       │                  │ canal-outbox topic    │                      │
 │                       │                  │──────────────────────>│                      │
 │                       │                  │                       │ 解析 payload         │
 │                       │                  │                       │ → RelationEvent      │
 │                       │                  │                       │─────────────────────>│
 │                       │                  │                       │                      │
 │                       │                  │                       │                      │ ① 去重 SETNX
 │                       │                  │                       │                      │ ② INSERT follower
 │                       │                  │                       │                      │ ③ 更新 ZSet 缓存
 │                       │                  │                       │                      │ ④ SDS 计数 +1
 │                       │                  │                       │                      │
 │                       │                  │  ack                  │                      │
 │                       │                  │<──────────────────────│                      │
```

---

## 七、可靠性保障

### 7.1 一致性保证

| 环节 | 机制 | 说明 |
|------|------|------|
| 主表 + outbox | `@Transactional` | 同一事务，原子写入 |
| Canal → Kafka | 批次 ack | 至少一次语义（at-least-once） |
| Kafka 消费 | 手动 ack | 处理成功才确认位点 |
| 事件处理 | Redis SETNX 去重 | 幂等保证，重复消息不会重复处理 |
| 计数系统 | 采样校验 + 按需重建 | 最终一致性，自动纠偏 |

### 7.2 异常处理

```
场景1: following 写入成功，outbox 写入失败
  → @Transactional 回滚，following 也撤销 ✓

场景2: Canal 桥接器崩溃
  → 重启后从上次 ack 的位点继续消费，不丢消息 ✓

场景3: Kafka 消费者处理失败
  → 不 ack，Kafka 会重新投递 ✓

场景4: 同一事件被处理两次
  → Redis SETNX 去重，第二次直接跳过 ✓

场景5: SDS 计数与 DB 不一致
  → 采样校验检测到 → 自动 rebuildAllCounters ✓
```

---

## 八、Redis Key 全景图

```
Redis
├── rl:follow:{userId}                    # 令牌桶限流
│   type: HASH {last, tokens}, TTL: 60s
│
├── uf:flws:{userId}                      # 关注列表 ZSet
│   type: ZSET, member=toUserId, score=timestamp, TTL: 2h
│
├── uf:fans:{userId}                      # 粉丝列表 ZSet
│   type: ZSET, member=fromUserId, score=timestamp, TTL: 2h
│
├── ucnt:{userId}                         # 用户计数 SDS
│   type: STRING (20字节二进制), 无 TTL
│
├── ucnt:chk:{userId}                     # 采样校验锁
│   type: STRING, TTL: 300s
│
└── dedup:rel:{type}:{from}:{to}:{id}     # 事件去重
    type: STRING, TTL: 10min
```

---

## 九、为什么这样设计？

### 9.1 为什么不直接同步写 follower 表？

```
同步写：
  follow() {
      followingMapper.insert(...)     // 主表
      followerMapper.insert(...)      // 从表
      // 两表在同一事务
  }

问题：
  - 事务粒度大，锁持有时间长
  - following 和 follower 表的写锁互相阻塞
  - 如果要加更多从表（如推荐系统），事务越来越大
  - 不符合单一职责原则
```

### 9.2 为什么不直接用 Kafka 而要经过 Canal？

```
方案A：代码直接发 Kafka
  follow() {
      followingMapper.insert(...)
      kafka.send("relation-events", event)   // 代码显式发送
  }

问题：
  - Kafka 发送失败怎么办？重试？补偿？
  - 代码和消息中间件强耦合
  - 需要自己处理事务和消息的一致性

方案B：Outbox + Canal（本项目）
  follow() {
      followingMapper.insert(...)
      outboxMapper.insert(...)    // 只写数据库
  }
  // Canal 自动监听 binlog → 转发 Kafka

优势：
  - 代码只操作数据库，不直接依赖 Kafka
  - Canal 基于 binlog，不侵入业务代码
  - 天然保证事务一致性（outbox 和 following 在同一事务）
  - 新增下游消费者只需订阅 Kafka topic，不改业务代码
```

### 9.3 一主多从的扩展性

```
当前从表/系统：
  ├── follower 表        ← Canal → Kafka → Consumer 写入
  └── SDS 计数           ← Canal → Kafka → Consumer 更新

未来可扩展：
  ├── 推荐系统           ← 订阅 canal-outbox，构建用户关系图谱
  ├── 消息通知系统        ← 订阅 canal-outbox，发送"XXX关注了你"
  └── 数据分析系统        ← 订阅 canal-outbox，统计关注趋势

所有从系统只需订阅同一个 Kafka topic，主表写入代码无需修改。
```

---

## 十、函数调用关系总览

```
RelationController
├── follow()           → RelationService.follow()
├── unfollow()         → RelationService.unfollow()
├── status()           → RelationService.relationStatus()
├── following()        → RelationService.followingProfiles()
├── followers()        → RelationService.followersProfiles()
└── counter()          → Redis GET ucnt:{userId} + 采样校验

RelationServiceImpl
├── follow()           → Lua 限流 + mapper.insertFollowing() + outboxMapper.insert()
├── unfollow()         → mapper.cancelFollowing() + outboxMapper.insert()
├── isFollowing()      → mapper.existsFollowing()
├── following()        → getListWithOffset() [L1→L2→L3]
├── followers()        → getListWithOffset() [L1→L2→L3]
├── followingCursor()  → getListWithCursor() [L2→L3]
├── followersCursor()  → getListWithCursor() [L2→L3]
├── followingProfiles() → following()/followingCursor() + toProfiles()
├── followersProfiles() → followers()/followersCursor() + toProfiles()
├── relationStatus()   → isFollowing() × 2
├── toProfiles()       → userMapper.listByIds() + 映射
├── isBigV()           → Redis GET ucnt:{userId} 解析 SDS 第2段
├── getListWithOffset() → Caffeine → Redis ZSet → MySQL 回填
├── getListWithCursor() → Redis ZSet rangeByScore → MySQL 回填
├── fillZSet()         → Redis ZADD
├── tsScore()          → 时间对象 → 毫秒时间戳
├── toLongList()       → Set<String> → List<Long>
└── maybeUpdateTopCache() → Redis ZREVRANGE 0:499 → Caffeine

CanalKafkaBridge (SmartLifecycle)
├── start()            → Canal 连接 + 订阅 + 主循环拉取 binlog
│   └── 循环: getWithoutAck() → 解析 RowChange → 提取 payload → kafka.send()
├── stop()             → running = false
└── isRunning()        → 返回状态

CanalOutboxConsumer
└── onMessage()        → extractRows() → 解析 payload → processor.process() → ack

RelationEventProcessor
└── process()          → 去重 SETNX + INSERT follower + ZSet 更新 + SDS 计数更新

UserCounterServiceImpl
├── incrementFollowings() → Lua 脚本原子更新 SDS 第1段
├── incrementFollowers()  → Lua 脚本原子更新 SDS 第2段
├── incrementPosts()      → Lua 脚本原子更新 SDS 第3段
├── incrementLikesReceived() → Lua 脚本原子更新 SDS 第4段
├── incrementFavsReceived()  → Lua 脚本原子更新 SDS 第5段
└── rebuildAllCounters()  → DB 查询全部计数 → 回写 SDS
```

---

*文档生成时间：2026-05-11 | 覆盖 relation + counter 模块*
