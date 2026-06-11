# 认证模块 (Auth Module) 学习指南

> 知光(ZhiGuang) 后端认证模块完整流程梳理，涵盖入口 -> 业务 -> 存储三层。

---

## 一、模块结构总览

```
com.tongji.auth/
├── api/                    # HTTP 入口层
│   ├── AuthController.java         # 7 个 REST 端点
│   └── dto/                        # 请求/响应 DTO（Java record）
│       ├── SendCodeRequest.java
│       ├── SendCodeResponse.java
│       ├── RegisterRequest.java
│       ├── LoginRequest.java
│       ├── TokenRefreshRequest.java
│       ├── LogoutRequest.java
│       ├── PasswordResetRequest.java
│       ├── AuthResponse.java       # = AuthUserResponse + TokenResponse
│       ├── AuthUserResponse.java
│       └── TokenResponse.java
├── service/                # 业务逻辑层
│   └── AuthService.java            # 核心编排器，所有认证逻辑在此
├── token/                  # JWT 令牌子系统
│   ├── JwtService.java             # RS256 签发 / 解析 / 校验
│   ├── TokenPair.java              # 内部 DTO：双令牌封装
│   ├── RefreshTokenStore.java      # 接口：刷新令牌白名单
│   └── RedisRefreshTokenStore.java # 实现：Redis 存储
├── verification/           # 验证码子系统
│   ├── VerificationService.java    # 验证码业务逻辑（限频、生成、校验）
│   ├── VerificationCodeStore.java  # 接口
│   ├── RedisVerificationCodeStore.java  # 实现：Redis Hash 存储
│   ├── CodeSender.java             # 接口：发送验证码
│   ├── LoggingCodeSender.java      # 实现：仅日志输出（开发环境）
│   ├── VerificationScene.java      # 枚举：REGISTER / LOGIN / RESET_PASSWORD
│   ├── VerificationCodeStatus.java # 枚举：校验结果状态
│   ├── SendCodeResult.java
│   └── VerificationCheckResult.java
├── audit/                  # 登录审计日志
│   ├── LoginLog.java               # 实体
│   ├── LoginLogMapper.java         # MyBatis Mapper
│   └── LoginLogService.java        # 写入 login_logs 表
├── config/                 # 配置层
│   ├── SecurityConfig.java         # Spring Security 过滤链
│   ├── AuthConfiguration.java      # Bean 注册（PasswordEncoder, JwtEncoder, JwtDecoder）
│   ├── AuthProperties.java         # 配置属性绑定 (auth.*)
│   └── PemUtils.java               # PEM 密钥文件读取工具
├── model/                  # 领域模型
│   ├── ClientInfo.java             # IP + User-Agent
│   └── IdentifierType.java         # 枚举：PHONE / EMAIL
└── util/
    └── IdentifierValidator.java    # 手机号/邮箱正则校验
```

---

## 二、API 端点一览

| 方法 | 路径 | 需要JWT | 功能 |
|------|------|---------|------|
| POST | `/api/v1/auth/send-code` | 否 | 发送验证码 |
| POST | `/api/v1/auth/register` | 否 | 注册（验证码 + 可选密码） |
| POST | `/api/v1/auth/login` | 否 | 登录（密码或验证码二选一） |
| POST | `/api/v1/auth/token/refresh` | 否 | 刷新令牌对 |
| POST | `/api/v1/auth/logout` | 否 | 登出（撤销刷新令牌） |
| POST | `/api/v1/auth/password/reset` | 否 | 重置密码（验证码 + 新密码） |
| GET  | `/api/v1/auth/me` | **是** | 获取当前用户信息 |

---

## 三、核心流程详解

### 3.1 发送验证码 (`POST /send-code`)

```
前端请求
  │  { scene, identifierType, identifier }
  ▼
AuthController.sendCode()
  │  校验入参
  ▼
AuthService.sendCode()
  │
  ├── 1. IdentifierValidator 校验格式
  │     手机: ^1\d{10}$
  │     邮箱: ^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$
  │
  ├── 2. normalizeIdentifier() — trim + 邮箱转小写
  │
  ├── 3. 存在性约束检查
  │     REGISTER → 账号必须不存在
  │     LOGIN/RESET_PASSWORD → 账号必须存在
  │
  └── 4. VerificationService.sendCode()
        │
        ├── 限频检查①：发送间隔 60s
        │   Redis key: auth:code:last:{scene}:{identifier}
        │   如果 key 存在 → 抛出 VERIFICATION_RATE_LIMIT
        │
        ├── 限频检查②：每日上限 10 次
        │   Redis key: auth:code:count:{scene}:{identifier}:{yyyyMMdd}
        │   INCR → 超限则抛出 VERIFICATION_DAILY_LIMIT
        │
        ├── SecureRandom 生成 6 位数字验证码
        │
        ├── RedisVerificationCodeStore.saveCode()
        │   HSET auth:code:{scene}:{identifier}
        │     code → "123456"
        │     maxAttempts → 5
        │     attempts → 0
        │   EXPIRE 300 秒 (5分钟)
        │
        └── CodeSender.sendCode()
            └── LoggingCodeSender → 仅打印日志（开发环境桩实现）
```

### 3.2 用户注册 (`POST /register`)

```
前端请求
  │  { identifierType, identifier, code, password?, agreeTerms }
  ▼
AuthController.register()
  ▼
AuthService.register()
  │
  ├── 1. agreeTerms 必须为 true，否则 TERMS_NOT_ACCEPTED
  │
  ├── 2. 校验标识符格式 + 唯一性
  │     findByPhone/findByEmail → 已存在则 IDENTIFIER_EXISTS
  │
  ├── 3. VerificationService.verify(scene=REGISTER, identifier, code)
  │     Redis HGETALL auth:code:REGISTER:{identifier}
  │     → NOT_FOUND / EXPIRED / TOO_MANY_ATTEMPTS / MISMATCH
  │     → 匹配成功则 DEL key（一次性使用）
  │
  ├── 4. 构建 User 实体
  │     phone 或 email
  │     nickname = "知光用户" + UUID前8位
  │     默认头像
  │
  ├── 5. 如有密码 → 校验策略（≥8位，必须含字母+数字）→ BCrypt 加密
  │
  ├── 6. UserService.createUser() → 数据库 INSERT INTO users
  │
  ├── 7. JwtService.issueTokenPair(user)
  │     ├─ Access Token: RS256, 15分钟
  │     │   claims: iss=zhiguang, sub=userId, jti=UUID, token_type=access, uid, nickname
  │     └─ Refresh Token: RS256, 7天
  │         claims: iss=zhiguang, sub=userId, jti=另一个UUID, token_type=refresh, uid
  │
  ├── 8. RedisRefreshTokenStore.storeToken()
  │     SET auth:rt:{userId}:{tokenId} "1" EX 604800
  │
  ├── 9. LoginLogService.record(channel=REGISTER, status=SUCCESS)
  │     INSERT INTO login_logs
  │
  └── 10. 返回 AuthResponse { user, token }
```

### 3.3 用户登录 (`POST /login`)

```
前端请求
  │  { identifierType, identifier, password? | code? }  ← 二选一
  ▼
AuthService.login()
  │
  ├── 1. 校验标识符格式
  │
  ├── 2. findUserByIdentifier()
  │     → 数据库 SELECT users WHERE phone/email = ?
  │     → 不存在则 IDENTIFIER_NOT_FOUND
  │
  ├── 3a. 密码登录路径：
  │     passwordEncoder.matches(rawPassword, hashedPassword)  // BCrypt
  │     → 失败：记录 FAILED 审计日志 → INVALID_CREDENTIALS
  │
  ├── 3b. 验证码登录路径：
  │     VerificationService.verify(scene=LOGIN, identifier, code)
  │     → 失败则抛出对应异常
  │
  ├── 4. issueTokenPair() → 签发双令牌
  │
  ├── 5. refreshTokenStore.storeToken() → Redis 白名单
  │
  ├── 6. loginLogService.record(channel=PASSWORD/CODE, status=SUCCESS)
  │
  └── 7. 返回 AuthResponse
```

### 3.4 刷新令牌 (`POST /token/refresh`)

```
前端请求
  │  { refreshToken }
  ▼
AuthService.refresh()
  │
  ├── 1. jwtService.decode(refreshToken) — 解码（不验签用于提取 claims）
  │     捕获 JwtException → REFRESH_TOKEN_INVALID
  │
  ├── 2. 校验 token_type == "refresh"
  │
  ├── 3. 提取 userId, tokenId(jti)
  │
  ├── 4. refreshTokenStore.isTokenValid(userId, tokenId)
  │     GET auth:rt:{userId}:{tokenId} → 必须 == "1"
  │     → 不存在则 REFRESH_TOKEN_INVALID
  │
  ├── 5. findUserById(userId) → 用户必须存在
  │
  ├── 6. issueTokenPair() → 签发**全新**令牌对
  │
  ├── 7. revokeToken(旧tokenId) → DEL auth:rt:{userId}:{oldTokenId}  ← 撤销旧令牌
  │
  ├── 8. storeToken(新tokenId) → SET auth:rt:{userId}:{newTokenId}   ← 存储新令牌
  │     ↑ 这就是「令牌轮换 (Token Rotation)」
  │
  └── 9. 返回新的 TokenResponse
```

### 3.5 用户登出 (`POST /logout`)

```
前端请求
  │  { refreshToken }
  ▼
AuthService.logout()
  │
  ├── 1. 安全解码 refreshToken（失败不报错，直接返回）
  │
  ├── 2. 校验 token_type == "refresh"
  │
  └── 3. refreshTokenStore.revokeToken(userId, tokenId)
        DEL auth:rt:{userId}:{tokenId}
```

### 3.6 重置密码 (`POST /password/reset`)

```
前端请求
  │  { identifierType, identifier, code, newPassword }
  ▼
AuthService.resetPassword()
  │
  ├── 1. 校验标识符格式 + 密码策略
  │
  ├── 2. findUserByIdentifier() → 用户必须存在
  │
  ├── 3. VerificationService.verify(scene=RESET_PASSWORD, identifier, code)
  │
  ├── 4. BCrypt 加密新密码 → UserService.updatePassword()
  │
  └── 5. refreshTokenStore.revokeAll(userId)
        KEYS auth:rt:{userId}:* → DEL 全部   ← 强制所有设备下线！
```

### 3.7 获取当前用户 (`GET /me`) — 需要 JWT

```
前端请求
  │  Header: Authorization: Bearer eyJhbGci...
  ▼
Spring Security 过滤链 (oauth2ResourceServer)
  │  1. 提取 Bearer token
  │  2. JwtDecoder 用公钥验签 + 校验过期时间 + issuer
  │  3. 注入 Jwt 对象到 @AuthenticationPrincipal
  ▼
AuthController.me(Jwt jwt)
  ▼
AuthService.me(jwt)
  │
  ├── 1. jwtService.extractUserId(jwt) → 从 sub claim 提取 userId
  │
  ├── 2. findUserById(userId) → 数据库查询
  │
  └── 3. 映射为 AuthUserResponse 返回
```

---

## 四、JWT 令牌系统

### 4.1 签名算法：RS256

- 使用 RSA 非对称密钥对
- 私钥签发 (`private.pem`)，公钥验证 (`public.pem`)
- 密钥文件位置：`src/main/resources/keys/`

### 4.2 令牌结构对比

| 属性 | Access Token | Refresh Token |
|------|-------------|---------------|
| 有效期 | 15 分钟 | 7 天 |
| 用途 | 访问受保护 API | 获取新的令牌对 |
| token_type | `"access"` | `"refresh"` |
| 包含 nickname | 是 | 否 |
| 存储位置 | 客户端内存 | 客户端持久化 |
| 可撤销 | 否（靠短过期） | 是（Redis 白名单） |

### 4.3 Access Token Claims 示例

```json
{
  "iss": "zhiguang",
  "sub": "12345",
  "iat": 1746182400,
  "exp": 1746183300,
  "jti": "a1b2c3d4-...",
  "token_type": "access",
  "uid": 12345,
  "nickname": "知光用户abcd1234"
}
```

### 4.4 令牌轮换机制

```
                    ┌─────────────────────────────────────┐
                    │           Token Rotation            │
                    └─────────────────────────────────────┘

第1次登录:  RT₁ 签发 → Redis 存储 auth:rt:user1:RT₁

刷新请求①: 验证 RT₁ 有效 → 签发 RT₂ → 撤销 RT₁ → 存储 RT₂
                              (Redis DEL RT₁)  (Redis SET RT₂)

刷新请求②: 验证 RT₂ 有效 → 签发 RT₃ → 撤销 RT₂ → 存储 RT₃

如果有人截获 RT₁ 并尝试使用:
  → Redis 中已无 RT₁ → REFRESH_TOKEN_INVALID ✓ 安全！
```

---

## 五、验证码系统

### 5.1 限频策略（双层防护）

```
第1层：发送间隔限制 (60秒)
  Redis key: auth:code:last:{scene}:{identifier}
  TTL: 60秒
  逻辑: key 存在则拒绝 → "验证码发送过于频繁"

第2层：每日次数限制 (10次/天)
  Redis key: auth:code:count:{scene}:{identifier}:{yyyyMMdd}
  TTL: 1天 (自动过期，第二天重置)
  逻辑: INCR → >10 则拒绝 → "验证码发送次数超限"
```

### 5.2 验证码存储 (Redis Hash)

```
Key:   auth:code:{scene}:{identifier}
TTL:   5 分钟
Type:  HASH

┌─────────────┬─────────┐
│ field       │ value   │
├─────────────┼─────────┤
│ code        │ "123456"│
│ maxAttempts │ "5"     │
│ attempts    │ "0"     │
└─────────────┴─────────┘
```

### 5.3 验证逻辑

```
verify(scene, identifier, code)
  │
  ├── HGETALL auth:code:{scene}:{identifier}
  │
  ├── 空? → NOT_FOUND（验证码不存在或已过期）
  │
  ├── attempts >= maxAttempts? → TOO_MANY_ATTEMPTS
  │
  ├── code 匹配?
  │   ├── 是 → DEL key（一次性使用） → SUCCESS
  │   └── 否 → HINCRBY attempts +1
  │            ├── attempts >= maxAttempts → EXPIRE 1800s（锁30分钟） → TOO_MANY_ATTEMPTS
  │            └── attempts < maxAttempts → MISMATCH
```

---

## 六、Redis Key 全景图

```
Redis
├── auth:rt:{userId}:{tokenId}                    # 刷新令牌白名单
│   type: STRING, value: "1", TTL: 7天
│
├── auth:code:{scene}:{identifier}                # 验证码存储
│   type: HASH {code, maxAttempts, attempts}, TTL: 5分钟
│
├── auth:code:last:{scene}:{identifier}           # 发送间隔限频
│   type: STRING, value: "1", TTL: 60秒
│
└── auth:code:count:{scene}:{identifier}:{date}   # 每日发送计数
    type: STRING, value: 数字, TTL: 1天
```

---

## 七、Security 配置要点

### 7.1 SecurityConfig 过滤链

```java
http
  .csrf(AbstractHttpConfigurer::disable)           // 无状态API，禁用CSRF
  .cors(cors -> cors.configurationSource(...))      // CORS: allowedOrigins=["*"]
  .sessionManagement(STATELESS)                     // 不使用Session
  .authorizeHttpRequests(auth -> auth
      .requestMatchers("/actuator/health").permitAll()
      .requestMatchers("/api/v1/auth/**").permitAll()  // 认证端点全部公开
      // ... 部分 knowposts 端点公开
      .anyRequest().authenticated()                  // 其余需要JWT
  )
  .oauth2ResourceServer(oauth -> oauth.jwt(...))   // JWT 验证过滤器
```

### 7.2 请求认证流程（受保护端点）

```
GET /api/v1/auth/me
Header: Authorization: Bearer eyJhbGci...

  │
  ▼
Spring Security OAuth2 Resource Server Filter
  │  1. 提取 Authorization header 中的 Bearer token
  │  2. JwtDecoder (NimbusJwtDecoder + 公钥) 验签
  │  3. 校验 exp (过期时间)、iss (签发者)
  │  4. 构建 Jwt 对象，存入 SecurityContext
  ▼
AuthController.me(@AuthenticationPrincipal Jwt jwt)
  │  直接拿到已验证的 Jwt 对象
  ▼
AuthService → JwtService.extractUserId(jwt)
  │  jwt.getSubject() → userId
  ▼
查询用户 → 返回
```

### 7.3 公开端点清单

| 端点 | 说明 |
|------|------|
| `/actuator/health`, `/actuator/info` | 健康检查 |
| `POST /api/v1/auth/send-code` | 发送验证码 |
| `POST /api/v1/auth/register` | 注册 |
| `POST /api/v1/auth/login` | 登录 |
| `POST /api/v1/auth/token/refresh` | 刷新令牌 |
| `POST /api/v1/auth/logout` | 登出 |
| `POST /api/v1/auth/password/reset` | 重置密码 |
| `GET /api/v1/knowposts/feed` | 知识帖 feed |
| `GET /api/v1/knowposts/detail/*` | 帖子详情 |
| `GET /api/v1/knowposts/*/qa/stream` | RAG QA 流 |

---

## 八、异常处理

### 8.1 认证相关错误码

| ErrorCode | 默认消息 | 触发场景 |
|-----------|---------|---------|
| IDENTIFIER_EXISTS | "账号已存在" | 注册时标识符已被占用 |
| IDENTIFIER_NOT_FOUND | "账号不存在" | 登录/重置密码时找不到用户 |
| VERIFICATION_RATE_LIMIT | "验证码发送过于频繁" | 60秒内重复发送 |
| VERIFICATION_DAILY_LIMIT | "验证码发送次数超限" | 超过每日10次 |
| VERIFICATION_NOT_FOUND | "验证码不存在或已过期" | 验证码不存在或已过5分钟 |
| VERIFICATION_MISMATCH | "验证码错误" | 输入的验证码不匹配 |
| VERIFICATION_TOO_MANY_ATTEMPTS | "验证码尝试次数过多" | 连续错误5次，锁定30分钟 |
| INVALID_CREDENTIALS | "登录凭证错误" | 密码不匹配 |
| PASSWORD_POLICY_VIOLATION | "密码强度不足" | 密码不符合策略 |
| TERMS_NOT_ACCEPTED | "请先同意服务条款" | 注册时未同意条款 |
| REFRESH_TOKEN_INVALID | "刷新令牌无效" | 刷新令牌解码失败或不在白名单 |
| BAD_REQUEST | "请求参数错误" | 登录时既没传密码也没传验证码 |

### 8.2 异常处理链

```
业务异常抛出
  │
  ▼
GlobalExceptionHandler (@RestControllerAdvice)
  ├── BusinessException        → HTTP 400, {code, message}
  ├── MethodArgumentNotValid   → HTTP 400, 第一个字段错误
  ├── ConstraintViolation      → HTTP 400
  └── Exception (兜底)         → HTTP 500, "服务异常，请稍后重试"
```

---

## 九、审计日志

登录/注册操作会写入 `login_logs` 表：

| 字段 | 说明 | 示例值 |
|------|------|--------|
| id | 主键 | 自增 |
| userId | 用户ID | 12345 |
| identifier | 手机号/邮箱 | "13800138000" |
| channel | 登录渠道 | "REGISTER" / "PASSWORD" / "CODE" |
| ip | 客户端IP | "192.168.1.1" |
| userAgent | 浏览器UA | "Mozilla/5.0..." |
| status | 结果 | "SUCCESS" / "FAILED" |
| created_at | 时间 | 自动填充 |

---

## 十、配置参数速查

```yaml
# application.yml 中 auth 相关配置
auth:
  jwt:
    issuer: zhiguang                    # JWT 签发者
    key-id: zhiguang-key               # JWK Key ID
    private-key: classpath:keys/private.pem
    public-key: classpath:keys/public.pem
    access-token-ttl: PT15M            # 15分钟
    refresh-token-ttl: P7D             # 7天
  verification:
    code-length: 6                     # 6位验证码
    ttl: PT5M                          # 5分钟有效期
    max-attempts: 5                    # 最多尝试5次
    send-interval: PT60S               # 60秒发送间隔
    daily-limit: 10                    # 每日最多10次
  password:
    bcrypt-strength: 12                # BCrypt 强度
    min-length: 8                      # 最短8位
```

---

## 十一、架构设计亮点

1. **双令牌机制**：Access Token 短效(15min)用于 API 认证，Refresh Token 长效(7天)用于续签，职责分离。

2. **Refresh Token 白名单**：只承认 Redis 中存在的刷新令牌，配合令牌轮换，旧令牌立即失效，防止重放攻击。

3. **密码重置 = 全局下线**：`revokeAll()` 删除用户所有刷新令牌，安全地强制所有设备重新登录。

4. **验证码双限频**：60秒间隔 + 每日10次上限，防止短信轰炸。

5. **验证码一次性使用**：验证成功立即 DEL，防止重复使用。

6. **无自定义 Filter**：完全依赖 Spring Security 内置的 `oauth2ResourceServer` 过滤链，减少代码复杂度。

7. **RS256 非对称签名**：私钥签发、公钥验证，未来可将公钥分发给其他微服务做独立验证。

---

## 十二、完整时序图：密码登录全流程

```
  前端                    AuthController         AuthService           UserService
   │                          │                      │                     │
   │  POST /auth/login        │                      │                     │
   │  {phone, password}       │                      │                     │
   │─────────────────────────>│                      │                     │
   │                          │                      │                     │
   │                          │  login(req, client)  │                     │
   │                          │─────────────────────>│                     │
   │                          │                      │                     │
   │                          │                      │  findByPhone()      │
   │                          │                      │────────────────────>│
   │                          │                      │  ← User             │
   │                          │                      │<────────────────────│
   │                          │                      │                     │
   │                          │                      │  BCrypt.matches()   │
   │                          │                      │  ────── OK ──────   │
   │                          │                      │                     │
   │                          │                      │  issueTokenPair()   │
   │                          │                      │  ┌──────────────┐   │
   │                          │                      │  │ JwtEncoder   │   │
   │                          │                      │  │ RS256 签发    │   │
   │                          │                      │  └──────────────┘   │
   │                          │                      │                     │
   │                          │                      │  Redis SET          │
   │                          │                      │  auth:rt:uid:tid    │
   │                          │                      │                     │
   │                          │                      │  INSERT login_logs  │
   │                          │                      │────────────────────>│
   │                          │                      │                     │
   │                          │  AuthResponse        │                     │
   │                          │<─────────────────────│                     │
   │  {user, token}           │                      │                     │
   │<─────────────────────────│                      │                     │
   │                          │                      │                     │
```

---

*文档生成时间：2026-05-02 | 基于 zhiguang_be-main 源码分析*
