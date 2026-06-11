# 认证模块全函数逐行分析

> 对 `com.tongji.auth` 包下所有文件中的每一个函数进行详细的执行流程分析。

---

## 目录

1. [AuthController — API 入口层](#1-authcontroller--api-入口层)
2. [AuthService — 核心业务逻辑](#2-authservice--核心业务逻辑)
3. [JwtService — JWT 令牌服务](#3-jwtservice--jwt-令牌服务)
4. [VerificationService — 验证码服务](#4-verificationservice--验证码服务)
5. [RedisVerificationCodeStore — 验证码 Redis 存储](#5-redisverificationcodestore--验证码-redis-存储)
6. [RedisRefreshTokenStore — 刷新令牌白名单](#6-redisrefreshtokenstore--刷新令牌白名单)
7. [LoginLogService — 审计日志](#7-loginlogservice--审计日志)
8. [SecurityConfig — 安全过滤链](#8-securityconfig--安全过滤链)
9. [AuthConfiguration — Bean 配置](#9-authconfiguration--bean-配置)
10. [AuthProperties — 配置属性](#10-authproperties--配置属性)
11. [PemUtils — PEM 密钥工具](#11-pemutils--pem-密钥工具)
12. [LoggingCodeSender — 验证码发送桩](#12-loggingcodesender--验证码发送桩)
13. [IdentifierValidator — 标识校验器](#13-identifiervalidator--标识校验器)
14. [模型/枚举/DTO/接口](#14-模型枚举dto接口)

---

## 1. AuthController — API 入口层

**文件:** `com.tongji.auth.api.AuthController`

### 1.1 `sendCode(SendCodeRequest request)` — 发送验证码

```java
@PostMapping("/send-code")
public SendCodeResponse sendCode(@Valid @RequestBody SendCodeRequest request)
```

**执行流程：**

1. Spring 自动反序列化 JSON → `SendCodeRequest` record
2. `@Valid` 触发 Jakarta 校验：`scene` 非空、`identifierType` 非空、`identifier` 非空
3. 校验失败 → `MethodArgumentNotValidException` → `GlobalExceptionHandler` → HTTP 400
4. 校验通过 → 调用 `authService.sendCode(request)`
5. 返回 `SendCodeResponse`，Spring 序列化为 JSON

### 1.2 `register(RegisterRequest request, HttpServletRequest httpRequest)` — 注册

```java
@PostMapping("/register")
public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest)
```

**执行流程：**

1. 反序列化 + `@Valid` 校验（identifierType、identifier、code 非空，agreeTerms 无校验）
2. 调用 `resolveClient(httpRequest)` 提取客户端信息 → `ClientInfo(ip, userAgent)`
3. 调用 `authService.register(request, clientInfo)`
4. 返回 `AuthResponse`（用户信息 + 令牌对）

### 1.3 `login(LoginRequest request, HttpServletRequest httpRequest)` — 登录

```java
@PostMapping("/login")
public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest)
```

**执行流程：**

1. 反序列化 + `@Valid` 校验（identifierType、identifier 非空；code 和 password 都可选）
2. `resolveClient(httpRequest)` → `ClientInfo`
3. `authService.login(request, clientInfo)`
4. 返回 `AuthResponse`

### 1.4 `refresh(TokenRefreshRequest request)` — 刷新令牌

```java
@PostMapping("/token/refresh")
public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request)
```

**执行流程：**

1. 反序列化 + `@Valid`（refreshToken 非空）
2. `authService.refresh(request)`
3. 返回 `TokenResponse`（新的双令牌）

### 1.5 `logout(LogoutRequest request)` — 登出

```java
@PostMapping("/logout")
public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request)
```

**执行流程：**

1. 反序列化 + `@Valid`（refreshToken 非空）
2. `authService.logout(request.refreshToken())` — 仅传 refreshToken 字符串
3. 返回 `ResponseEntity.noContent()` — HTTP 204，无响应体

### 1.6 `resetPassword(PasswordResetRequest request)` — 重置密码

```java
@PostMapping("/password/reset")
public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request)
```

**执行流程：**

1. 反序列化 + `@Valid`（identifierType、identifier、code、newPassword 均非空）
2. `authService.resetPassword(request)`
3. 返回 HTTP 204

### 1.7 `me(@AuthenticationPrincipal Jwt jwt)` — 获取当前用户

```java
@GetMapping("/me")
public AuthUserResponse me(@AuthenticationPrincipal Jwt jwt)
```

**执行流程：**

1. Spring Security 的 `oauth2ResourceServer` 过滤器自动处理：
   - 提取 `Authorization: Bearer <token>`
   - 用公钥验签 + 校验 exp/iss
   - 构建 `Jwt` 对象注入参数
2. `jwtService.extractUserId(jwt)` → 从 `uid` claim 提取 userId
3. `authService.me(userId)` → 查用户 → 返回 `AuthUserResponse`

### 1.8 `resolveClient(HttpServletRequest request)` — 解析客户端信息

```java
private ClientInfo resolveClient(HttpServletRequest request)
```

**执行流程：**

1. `extractClientIp(request)` 获取 IP
2. `request.getHeader("User-Agent")` 获取 UA
3. 返回 `new ClientInfo(ip, ua)`

### 1.9 `extractClientIp(HttpServletRequest request)` — 提取客户端 IP

```java
private String extractClientIp(HttpServletRequest request)
```

**执行流程（优先级从高到低）：**

1. 读取 `X-Forwarded-For` 头 → 非空则取第一个逗号前的值（代理场景）
2. 读取 `X-Real-IP` 头 → 非空则使用
3. 回退到 `request.getRemoteAddr()`（直连 IP）

---

## 2. AuthService — 核心业务逻辑

**文件:** `com.tongji.auth.service.AuthService`

**依赖注入：** `UserService`、`VerificationService`、`PasswordEncoder`、`JwtService`、`RefreshTokenStore`、`LoginLogService`、`AuthProperties`

### 2.1 `sendCode(SendCodeRequest request)` — 发送验证码

```java
public SendCodeResponse sendCode(SendCodeRequest request)
```

**执行流程：**

1. `validateIdentifier(request.identifierType(), request.identifier())`
   - PHONE → 正则 `^1\d{10}$`
   - EMAIL → 正则标准邮箱格式
   - 不匹配 → 抛 `BusinessException(BAD_REQUEST)`
2. `normalizeIdentifier(...)` — trim + 邮箱转小写
3. `identifierExists(type, normalized)` — 查数据库判断账号是否已注册
4. 场景约束：
   - REGISTER + 已存在 → 抛 `IDENTIFIER_EXISTS`
   - LOGIN/RESET_PASSWORD + 不存在 → 抛 `IDENTIFIER_NOT_FOUND`
5. `verificationService.sendCode(scene, normalized)` → 限频 + 生成 + 存储 + 发送
6. 构建 `SendCodeResponse(identifier, scene, expireSeconds)` 返回

### 2.2 `register(RegisterRequest request, ClientInfo clientInfo)` — 注册

```java
public AuthResponse register(RegisterRequest request, ClientInfo clientInfo)
```

**执行流程：**

1. `request.agreeTerms()` 必须为 `true` → 否则抛 `TERMS_NOT_ACCEPTED`
2. `validateIdentifier(...)` + `normalizeIdentifier(...)` — 格式校验与标准化
3. `identifierExists(...)` → 已存在则抛 `IDENTIFIER_EXISTS`
4. `verificationService.verify(REGISTER, identifier, code)` → 校验验证码
   - `ensureVerificationSuccess(result)` — 失败则按状态抛出对应异常
5. 构建 `User` 实体：
   - phone 或 email 按类型设置
   - nickname = `"知光用户" + UUID前8位`
   - avatar = 默认头像 URL
   - tagsJson = `"[]"`
6. 如果传了 password：
   - `validatePassword(password)` — 非空、≥8位、必须含字母+数字
   - `passwordEncoder.encode(password)` — BCrypt 加密
   - `user.setPasswordHash(...)`
7. `userService.createUser(user)` → 数据库 INSERT
8. `jwtService.issueTokenPair(user)` → 签发 Access + Refresh Token
9. `storeRefreshToken(userId, tokenPair)` → `SET auth:rt:{uid}:{tid} "1" EX {ttl}`
10. `loginLogService.record(userId, identifier, "REGISTER", ip, ua, "SUCCESS")`
11. 返回 `AuthResponse(mapUser(user), mapToken(tokenPair))`

### 2.3 `login(LoginRequest request, ClientInfo clientInfo)` — 登录

```java
public AuthResponse login(LoginRequest request, ClientInfo clientInfo)
```

**执行流程：**

1. `validateIdentifier(...)` + `normalizeIdentifier(...)` — 格式校验与标准化
2. `findUserByIdentifier(type, identifier)` → 数据库查询
   - 不存在 → 抛 `IDENTIFIER_NOT_FOUND`
3. 凭证校验（二选一）：
   - **password 路径：**
     - `passwordEncoder.matches(rawPassword, hashedPassword)` — BCrypt 比对
     - 失败 → `loginLogService.record(..., "FAILED")` → 抛 `INVALID_CREDENTIALS`
   - **code 路径：**
     - `verificationService.verify(LOGIN, identifier, code)` → 验证码校验
     - `ensureVerificationSuccess(result)`
   - **都没有：** 抛 `BAD_REQUEST("请提供验证码或密码")`
4. `jwtService.issueTokenPair(user)` → 签发双令牌
5. `storeRefreshToken(userId, tokenPair)` → Redis 白名单存储
6. `loginLogService.record(userId, identifier, channel, ip, ua, "SUCCESS")`
7. 返回 `AuthResponse(mapUser(user), mapToken(tokenPair))`

### 2.4 `refresh(TokenRefreshRequest request)` — 刷新令牌

```java
public TokenResponse refresh(TokenRefreshRequest request)
```

**执行流程：**

1. `decodeRefreshToken(request.refreshToken())` — 解码 JWT
   - `JwtException` → 抛 `REFRESH_TOKEN_INVALID`
2. `jwtService.extractTokenType(jwt)` → 必须等于 `"refresh"`，否则抛异常
3. `jwtService.extractUserId(jwt)` → 提取 userId
4. `jwtService.extractTokenId(jwt)` → 提取 tokenId (jti)
5. `refreshTokenStore.isTokenValid(userId, tokenId)` → 查 Redis 白名单
   - 不存在 → 抛 `REFRESH_TOKEN_INVALID`
6. `findUserById(userId)` → 用户必须存在
7. `jwtService.issueTokenPair(user)` → 签发**全新**令牌对
8. `refreshTokenStore.revokeToken(userId, tokenId)` → 撤销**旧**刷新令牌
9. `storeRefreshToken(userId, tokenPair)` → 存储**新**刷新令牌
10. 返回 `mapToken(tokenPair)`

### 2.5 `logout(String refreshToken)` — 登出

```java
public void logout(String refreshToken)
```

**执行流程：**

1. `decodeRefreshTokenSafely(refreshToken)` — 安全解码，失败返回 `Optional.empty()`（不报错）
2. 如果解码成功 且 `token_type == "refresh"`：
   - `jwtService.extractUserId(jwt)` → userId
   - `jwtService.extractTokenId(jwt)` → tokenId
   - `refreshTokenStore.revokeToken(userId, tokenId)` → DEL Redis key
3. 否则什么也不做（静默返回）

### 2.6 `resetPassword(PasswordResetRequest request)` — 重置密码

```java
public void resetPassword(PasswordResetRequest request)
```

**执行流程：**

1. `validateIdentifier(...)` — 格式校验
2. `validatePassword(request.newPassword())` — 密码策略校验
3. `normalizeIdentifier(...)` — 标准化
4. `findUserByIdentifier(...)` → 不存在则抛 `IDENTIFIER_NOT_FOUND`
5. `verificationService.verify(RESET_PASSWORD, identifier, code)` → 验证码校验
6. `ensureVerificationSuccess(result)`
7. `passwordEncoder.encode(newPassword)` → BCrypt 加密
8. `userService.updatePassword(user)` → 数据库 UPDATE
9. `refreshTokenStore.revokeAll(userId)` → **删除该用户所有刷新令牌，强制全设备下线**

### 2.7 `me(long userId)` — 查询当前用户

```java
public AuthUserResponse me(long userId)
```

**执行流程：**

1. `findUserById(userId)` → 数据库查询
   - 不存在 → 抛 `IDENTIFIER_NOT_FOUND`
2. `mapUser(user)` → 映射为 `AuthUserResponse` 返回

### 2.8 `ensureVerificationSuccess(VerificationCheckResult result)` — 校验码结果断言

```java
private void ensureVerificationSuccess(VerificationCheckResult result)
```

**执行流程：**

1. `result.isSuccess()` → true 则直接返回
2. 按状态映射异常：
   - `NOT_FOUND` / `EXPIRED` → `VERIFICATION_NOT_FOUND`
   - `MISMATCH` → `VERIFICATION_MISMATCH`
   - `TOO_MANY_ATTEMPTS` → `VERIFICATION_TOO_MANY_ATTEMPTS`
   - 其他 → `BAD_REQUEST("验证码校验失败")`

### 2.9 `validateIdentifier(IdentifierType type, String identifier)` — 格式校验

```java
private void validateIdentifier(IdentifierType type, String identifier)
```

**执行流程：**

1. PHONE + `!IdentifierValidator.isValidPhone()` → 抛 `BAD_REQUEST("手机号格式错误")`
2. EMAIL + `!IdentifierValidator.isValidEmail()` → 抛 `BAD_REQUEST("邮箱格式错误")`

### 2.10 `validatePassword(String password)` — 密码策略校验

```java
private void validatePassword(String password)
```

**执行流程：**

1. `!StringUtils.hasText(password)` → 抛 `PASSWORD_POLICY_VIOLATION("密码不能为空")`
2. `password.trim()`
3. `trimmed.length() < minLength`（默认8） → 抛 `"密码至少X位"`
4. `chars.anyMatch(isLetter)` + `chars.anyMatch(isDigit)` → 缺一则抛 `"密码需包含字母和数字"`

### 2.11 `identifierExists(IdentifierType type, String identifier)` — 判断标识是否存在

```java
private boolean identifierExists(IdentifierType type, String identifier)
```

**执行流程：**

- `PHONE` → `userService.existsByPhone(identifier)` → 数据库查询
- `EMAIL` → `userService.existsByEmail(identifier)` → 数据库查询

### 2.12 `findUserByIdentifier(IdentifierType type, String identifier)` — 按标识查用户

```java
private Optional<User> findUserByIdentifier(IdentifierType type, String identifier)
```

**执行流程：**

- `PHONE` → `userService.findByPhone(identifier)`
- `EMAIL` → `userService.findByEmail(identifier)`

### 2.13 `findUserById(long userId)` — 按 ID 查用户

```java
private Optional<User> findUserById(long userId)
```

**执行流程：**

- `userService.findById(userId)` → 数据库 `SELECT * FROM users WHERE id = ?`

### 2.14 `normalizeIdentifier(IdentifierType type, String identifier)` — 标准化标识

```java
private String normalizeIdentifier(IdentifierType type, String identifier)
```

**执行流程：**

- `PHONE` → `identifier.trim()`
- `EMAIL` → `identifier.trim().toLowerCase(Locale.ROOT)`

### 2.15 `storeRefreshToken(Long userId, TokenPair tokenPair)` — 存储刷新令牌

```java
private void storeRefreshToken(Long userId, TokenPair tokenPair)
```

**执行流程：**

1. `ttl = Duration.between(now, tokenPair.refreshTokenExpiresAt())`
2. ttl 为负则置为 `Duration.ZERO`
3. `refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl)`
   → `SET auth:rt:{uid}:{tid} "1" EX {seconds}`

### 2.16 `mapUser(User user)` — 用户实体 → 响应 DTO

```java
private AuthUserResponse mapUser(User user)
```

**执行流程：**

- 从 `User` 提取字段，构建 `AuthUserResponse(id, nickname, avatar, phone, zgId, birthday, school, bio, gender, tagsJson)`

### 2.17 `mapToken(TokenPair tokenPair)` — 令牌对 → 响应 DTO

```java
private TokenResponse mapToken(TokenPair tokenPair)
```

**执行流程：**

- `new TokenResponse(accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt)`

### 2.18 `generateNickname()` — 生成默认昵称

```java
private String generateNickname()
```

**执行流程：**

- 返回 `"知光用户" + UUID.randomUUID().toString().substring(0, 8)`
- 示例：`"知光用户a1b2c3d4"`

### 2.19 `decodeRefreshToken(String refreshToken)` — 解码刷新令牌（失败抛异常）

```java
private Jwt decodeRefreshToken(String refreshToken)
```

**执行流程：**

1. `jwtService.decode(refreshToken)` — 用公钥解码 JWT
2. 成功 → 返回 `Jwt`
3. `JwtException` → 抛 `REFRESH_TOKEN_INVALID`

### 2.20 `decodeRefreshTokenSafely(String refreshToken)` — 安全解码（失败返回空）

```java
private Optional<Jwt> decodeRefreshTokenSafely(String refreshToken)
```

**执行流程：**

1. `jwtService.decode(refreshToken)` → 成功返回 `Optional.of(jwt)`
2. `JwtException` → 返回 `Optional.empty()`（不抛异常）

---

## 3. JwtService — JWT 令牌服务

**文件:** `com.tongji.auth.token.JwtService`

### 3.1 `issueTokenPair(User user)` — 签发令牌对

```java
public TokenPair issueTokenPair(User user)
```

**执行流程：**

1. `refreshTokenId = UUID.randomUUID().toString()` — 生成刷新令牌唯一 ID
2. `issuedAt = Instant.now(clock)` — 当前 UTC 时间
3. `accessExpiresAt = issuedAt + 15分钟`
4. `refreshExpiresAt = issuedAt + 7天`
5. `encodeToken(user, issuedAt, accessExpiresAt, "access", randomUUID)` — 签发 Access Token
   - claims: iss="zhiguang", sub=userId, iat, exp, jti=randomUUID, token_type="access", uid=userId, nickname
   - `jwtEncoder.encode(...)` — RS256 私钥签名
6. `encodeRefreshToken(user, issuedAt, refreshExpiresAt, refreshTokenId)` — 签发 Refresh Token
   - claims: iss, sub, iat, exp, jti=refreshTokenId, token_type="refresh", uid
   - **无 nickname**
7. 返回 `TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, refreshTokenId)`

### 3.2 `decode(String token)` — 解码 JWT

```java
public Jwt decode(String token)
```

**执行流程：**

- `jwtDecoder.decode(token)` — 用公钥验签 + 校验 exp/iss
- 失败抛 `JwtException`

### 3.3 `encodeToken(User user, Instant issuedAt, Instant expiresAt, String tokenType, String tokenId)` — 编码 Access Token

```java
private String encodeToken(User user, Instant issuedAt, Instant expiresAt, String tokenType, String tokenId)
```

**执行流程：**

1. 构建 `JwtClaimsSet`：
   - `issuer` = "zhiguang"
   - `issuedAt` = 签发时间
   - `expiresAt` = 过期时间
   - `subject` = userId 字符串
   - `id` = tokenId (jti)
   - `token_type` = "access"
   - `uid` = userId 数值
   - `nickname` = 用户昵称
2. `jwtEncoder.encode(JwtEncoderParameters.from(claims))` — RS256 私钥签名
3. `.getTokenValue()` → 返回 JWT 字符串

### 3.4 `encodeRefreshToken(User user, Instant issuedAt, Instant expiresAt, String tokenId)` — 编码 Refresh Token

```java
private String encodeRefreshToken(User user, Instant issuedAt, Instant expiresAt, String tokenId)
```

**执行流程：**

1. 构建 `JwtClaimsSet`：
   - `issuer`, `issuedAt`, `expiresAt`, `subject` = userId
   - `id` = tokenId (jti) — **这个 jti 是白名单的 key**
   - `token_type` = "refresh"
   - `uid` = userId
   - **无 nickname**
2. `jwtEncoder.encode(...)` → JWT 字符串

### 3.5 `extractUserId(Jwt jwt)` — 提取用户 ID

```java
public long extractUserId(Jwt jwt)
```

**执行流程：**

1. `jwt.getClaims().get("uid")` → 取 `uid` claim
2. `instanceof Number` → `number.longValue()`
3. `instanceof String` → `Long.parseLong(text)`
4. 都不是 → 抛 `IllegalArgumentException("Invalid user id in token")`

### 3.6 `extractTokenType(Jwt jwt)` — 提取令牌类型

```java
public String extractTokenType(Jwt jwt)
```

**执行流程：**

1. `jwt.getClaims().get("token_type")`
2. 非空 → `.toString()` → 返回 `"access"` 或 `"refresh"`
3. 为空 → 返回空字符串 `""`

### 3.7 `extractTokenId(Jwt jwt)` — 提取令牌 ID

```java
public String extractTokenId(Jwt jwt)
```

**执行流程：**

- `jwt.getId()` → 返回 jti claim 值

---

## 4. VerificationService — 验证码服务

**文件:** `com.tongji.auth.verification.VerificationService`

### 4.1 `sendCode(VerificationScene scene, String identifier)` — 发送验证码

```java
public SendCodeResult sendCode(VerificationScene scene, String identifier)
```

**执行流程：**

1. 参数校验：scene 非空 + identifier 非空 → 否则抛 `BAD_REQUEST`
2. 读取配置 `properties.getVerification()`
3. `enforceSendInterval(scene, identifier, sendInterval)` — 60秒间隔限频
4. `enforceDailyLimit(scene, identifier, dailyLimit)` — 每日10次上限
5. `generateNumericCode(codeLength)` — SecureRandom 生成 6 位数字码
6. `codeStore.saveCode(scene.name(), identifier, code, ttl, maxAttempts)`
   → Redis HSET `auth:code:{scene}:{identifier}` {code, maxAttempts, attempts=0} + EXPIRE
7. `codeSender.sendCode(scene, identifier, code, expireMinutes)`
   → LoggingCodeSender 仅打印日志
8. 返回 `SendCodeResult(identifier, scene, expireSeconds)`

### 4.2 `verify(VerificationScene scene, String identifier, String code)` — 校验验证码

```java
public VerificationCheckResult verify(VerificationScene scene, String identifier, String code)
```

**执行流程：**

1. 参数校验：scene + identifier + code 均非空
2. `codeStore.verify(scene.name(), identifier, code)` → 委托给 Redis 实现
3. 返回 `VerificationCheckResult`

### 4.3 `invalidate(VerificationScene scene, String identifier)` — 使验证码失效

```java
public void invalidate(VerificationScene scene, String identifier)
```

**执行流程：**

- `codeStore.invalidate(scene.name(), identifier)` → DEL Redis key

### 4.4 `enforceSendInterval(VerificationScene scene, String identifier, Duration interval)` — 发送间隔限频

```java
private void enforceSendInterval(VerificationScene scene, String identifier, Duration interval)
```

**执行流程：**

1. `interval` 为 0 或负 → 跳过
2. 构建 key = `"auth:code:last:{scene}:{identifier}"`
3. `stringRedisTemplate.opsForValue().get(key)` → 查 Redis
4. key 存在 → 抛 `VERIFICATION_RATE_LIMIT`（60秒内重复发送）
5. key 不存在 → `SET key "1" EX {interval秒}`

### 4.5 `enforceDailyLimit(VerificationScene scene, String identifier, int limit)` — 每日限额

```java
private void enforceDailyLimit(VerificationScene scene, String identifier, int limit)
```

**执行流程：**

1. `limit <= 0` → 跳过
2. `date = LocalDate.now().format("yyyyMMdd")`
3. key = `"auth:code:count:{scene}:{identifier}:{date}"`
4. `increment(key)` → Redis INCR，返回当前计数
5. 如果 count == 1（首次）→ `expire(key, 1天)`
6. 如果 count > limit → 抛 `VERIFICATION_DAILY_LIMIT`

### 4.6 `generateNumericCode(int length)` — 生成数字验证码

```java
private static String generateNumericCode(int length)
```

**执行流程：**

1. 循环 `length` 次（默认6次）
2. 每次 `SecureRandom.nextInt(10)` → 随机 0-9
3. 拼接为字符串返回，如 `"839201"`

---

## 5. RedisVerificationCodeStore — 验证码 Redis 存储

**文件:** `com.tongji.auth.verification.RedisVerificationCodeStore`

### 5.1 `saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts)` — 保存验证码

```java
public void saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts)
```

**执行流程：**

1. `key = "auth:code:{scene}:{identifier}"`
2. `HSET key code "839201"`
3. `HSET key maxAttempts "5"`
4. `HSET key attempts "0"`
5. `EXPIRE key 300`（5分钟）
6. `DataAccessException` → 包装为 `RedisSystemException` 抛出

### 5.2 `verify(String scene, String identifier, String code)` — 校验验证码

```java
public VerificationCheckResult verify(String scene, String identifier, String code)
```

**执行流程：**

1. `key = "auth:code:{scene}:{identifier}"`
2. `HGETALL key` → 获取全部字段
3. **data 为空** → 返回 `NOT_FOUND`（验证码不存在或已过期）
4. 解析 `storedCode`、`maxAttempts`（默认5）、`attempts`（默认0）
5. **attempts >= maxAttempts** → 返回 `TOO_MANY_ATTEMPTS`（已锁定）
6. **storedCode == code**（匹配成功）：
   - `DEL key` — 一次性使用，立即删除
   - 返回 `SUCCESS`
7. **不匹配：**
   - `updatedAttempts = attempts + 1`
   - `HSET key attempts updatedAttempts`
   - 如果 `updatedAttempts >= maxAttempts`：
     - `EXPIRE key 1800`（锁定30分钟）
     - 返回 `TOO_MANY_ATTEMPTS`
   - 否则返回 `MISMATCH`

### 5.3 `invalidate(String scene, String identifier)` — 使验证码失效

```java
public void invalidate(String scene, String identifier)
```

**执行流程：**

- `redisTemplate.delete("auth:code:{scene}:{identifier}")`

### 5.4 `buildKey(String scene, String identifier)` — 构建 Redis key

```java
private static String buildKey(String scene, String identifier)
```

**执行流程：**

- 返回 `"auth:code:{scene}:{identifier}"`

### 5.5 `parseInt(String value, int defaultValue)` — 安全解析整数

```java
private static int parseInt(String value, int defaultValue)
```

**执行流程：**

1. `value == null` → 返回 `defaultValue`
2. `Integer.parseInt(value)` → 成功返回解析值
3. `NumberFormatException` → 返回 `defaultValue`

---

## 6. RedisRefreshTokenStore — 刷新令牌白名单

**文件:** `com.tongji.auth.token.RedisRefreshTokenStore`

### 6.1 `storeToken(long userId, String tokenId, Duration ttl)` — 存储令牌

```java
public void storeToken(long userId, String tokenId, Duration ttl)
```

**执行流程：**

1. `key = "auth:rt:{userId}:{tokenId}"`
2. `SET key "1" EX {ttl秒}`

### 6.2 `isTokenValid(long userId, String tokenId)` — 校验令牌有效性

```java
public boolean isTokenValid(long userId, String tokenId)
```

**执行流程：**

1. `key = "auth:rt:{userId}:{tokenId}"`
2. `GET key`
3. 返回 `value == "1"`

### 6.3 `revokeToken(long userId, String tokenId)` — 撤销单个令牌

```java
public void revokeToken(long userId, String tokenId)
```

**执行流程：**

- `DEL auth:rt:{userId}:{tokenId}`

### 6.4 `revokeAll(long userId)` — 撤销用户全部令牌

```java
public void revokeAll(long userId)
```

**执行流程：**

1. `pattern = "auth:rt:{userId}:*"`
2. `KEYS pattern` — 模糊搜索所有匹配的 key
3. `keys` 非空 → `DEL keys` — 批量删除

### 6.5 `key(long userId, String tokenId)` — 构建 Redis key

```java
private static String key(long userId, String tokenId)
```

**执行流程：**

- 返回 `"auth:rt:{userId}:{tokenId}"`

---

## 7. LoginLogService — 审计日志

**文件:** `com.tongji.auth.audit.LoginLogService`

### 7.1 `record(Long userId, String identifier, String channel, String ip, String userAgent, String status)` — 记录日志

```java
@Transactional
public void record(Long userId, String identifier, String channel, String ip, String userAgent, String status)
```

**执行流程：**

1. 构建 `LoginLog` 实体：
   - userId, identifier, channel, ip, userAgent, status
   - `createdAt = Instant.now()`
2. `loginLogMapper.insert(log)` → MyBatis 执行 `INSERT INTO login_logs`
3. `@Transactional` 保证写入原子性

---

## 8. SecurityConfig — 安全过滤链

**文件:** `com.tongji.auth.config.SecurityConfig`

### 8.1 `securityFilterChain(HttpSecurity http)` — 安全过滤链配置

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http)
```

**执行流程：**

1. `.csrf(AbstractHttpConfigurer::disable)` — 禁用 CSRF（无状态 API）
2. `.cors(Customizer.withDefaults())` — 启用 CORS，使用下方定义的配置源
3. `.sessionManagement(STATELESS)` — 不创建 HttpSession
4. `.authorizeHttpRequests(...)` — 路由权限：
   - `/actuator/health`, `/actuator/info` → permitAll
   - `GET /api/v1/knowposts/feed` → permitAll
   - `GET /api/v1/knowposts/detail/*` → permitAll
   - `GET /api/v1/knowposts/*/qa/stream` → permitAll
   - 6 个 auth 端点 → permitAll
   - 其余所有 → `authenticated()`
5. `.oauth2ResourceServer(oauth -> oauth.jwt(...))` — 启用 JWT 验证过滤器
6. `return http.build()` → 返回过滤链 Bean

### 8.2 `corsConfigurationSource()` — CORS 配置

```java
@Bean
public CorsConfigurationSource corsConfigurationSource()
```

**执行流程：**

1. `allowedOrigins = ["*"]` — 允许所有来源（TODO：需替换为白名单）
2. `allowedMethods = [GET, POST, PUT, DELETE, OPTIONS]`
3. `allowedHeaders = [Authorization, Content-Type, X-Requested-With]`
4. `allowCredentials = false`
5. 注册到 `/**` 路径

---

## 9. AuthConfiguration — Bean 配置

**文件:** `com.tongji.auth.config.AuthConfiguration`

### 9.1 `passwordEncoder()` — 密码编码器

```java
@Bean
public PasswordEncoder passwordEncoder()
```

**执行流程：**

- `new BCryptPasswordEncoder(authProperties.getPassword().getBcryptStrength())`
- strength 默认 12

### 9.2 `jwtEncoder()` — JWT 编码器

```java
@Bean
public JwtEncoder jwtEncoder()
```

**执行流程：**

1. `PemUtils.readPrivateKey(privateKeyResource)` → 读取 PEM → `RSAPrivateKey`
2. `PemUtils.readPublicKey(publicKeyResource)` → 读取 PEM → `RSAPublicKey`
3. `new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("zhiguang-key").build()` → 构建 JWK
4. `new ImmutableJWKSet<>(new JWKSet(jwk))` → JWK Source
5. `new NimbusJwtEncoder(jwkSource)` → 返回编码器

### 9.3 `jwtDecoder()` — JWT 解码器

```java
@Bean
public JwtDecoder jwtDecoder()
```

**执行流程：**

1. `PemUtils.readPublicKey(publicKeyResource)` → 读取公钥
2. `NimbusJwtDecoder.withPublicKey(publicKey).build()` → 返回解码器
3. 解码时自动验签 + 校验 exp/iss

---

## 10. AuthProperties — 配置属性

**文件:** `com.tongji.auth.config.AuthProperties`

### 10.1 内部类 `Jwt`

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `issuer` | String | "zhiguang" | JWT iss claim |
| `accessTokenTtl` | Duration | 15分钟 | Access Token 有效期 |
| `refreshTokenTtl` | Duration | 7天 | Refresh Token 有效期 |
| `keyId` | String | "zhiguang-key" | JWK Key ID |
| `privateKey` | Resource | - | RSA 私钥 PEM 路径 |
| `publicKey` | Resource | - | RSA 公钥 PEM 路径 |

### 10.2 内部类 `Verification`

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `codeLength` | int | 6 | 验证码位数 |
| `ttl` | Duration | 5分钟 | 验证码有效期 |
| `maxAttempts` | int | 5 | 最大尝试次数 |
| `sendInterval` | Duration | 60秒 | 发送间隔 |
| `dailyLimit` | int | 10 | 每日发送上限 |

### 10.3 内部类 `Password`

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `bcryptStrength` | int | 12 | BCrypt cost factor |
| `minLength` | int | 8 | 最短密码长度 |

---

## 11. PemUtils — PEM 密钥工具

**文件:** `com.tongji.auth.config.PemUtils`

### 11.1 `readPrivateKey(Resource resource)` — 读取 RSA 私钥

```java
public static RSAPrivateKey readPrivateKey(Resource resource)
```

**执行流程：**

1. `readResource(resource)` → 读取 PEM 文件全文
2. 去除 `-----BEGIN PRIVATE KEY-----` 和 `-----END PRIVATE KEY-----`
3. `replaceAll("\\s", "")` — 去除所有空白
4. `Base64.getDecoder().decode(keyData)` → 二进制
5. `new PKCS8EncodedKeySpec(keyBytes)` → PKCS#8 规范
6. `KeyFactory.getInstance("RSA").generatePrivate(spec)` → `RSAPrivateKey`
7. 异常 → `IllegalStateException("Failed to read RSA private key")`

### 11.2 `readPublicKey(Resource resource)` — 读取 RSA 公钥

```java
public static RSAPublicKey readPublicKey(Resource resource)
```

**执行流程：**

1. 读取 PEM → 去头尾 → 去空白 → Base64 解码
2. `new X509EncodedKeySpec(keyBytes)` → X.509 规范
3. `KeyFactory.getInstance("RSA").generatePublic(spec)` → `RSAPublicKey`

### 11.3 `readResource(Resource resource)` — 读取资源文本

```java
private static String readResource(Resource resource)
```

**执行流程：**

1. `resource.getInputStream()` → 获取输入流
2. `is.readAllBytes()` → 读取全部字节
3. `new String(bytes, UTF_8)` → 返回字符串

---

## 12. LoggingCodeSender — 验证码发送桩

**文件:** `com.tongji.auth.verification.LoggingCodeSender`

### 12.1 `sendCode(VerificationScene scene, String identifier, String code, int expireMinutes)` — 发送验证码

```java
public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes)
```

**执行流程：**

- `log.info("Send verification code scene={} identifier={} code={} expireMinutes={}", ...)`
- **仅打印日志，不实际发送**（开发环境桩实现）

---

## 13. IdentifierValidator — 标识校验器

**文件:** `com.tongji.auth.util.IdentifierValidator`

### 13.1 `isValidPhone(String phone)` — 校验手机号

```java
public static boolean isValidPhone(String phone)
```

**执行流程：**

1. `phone == null` → 返回 `false`
2. `PHONE_PATTERN.matcher(phone).matches()` — 正则 `^1\d{10}$`
3. 匹配 → `true`，否则 `false`

### 13.2 `isValidEmail(String email)` — 校验邮箱

```java
public static boolean isValidEmail(String email)
```

**执行流程：**

1. `email == null` → 返回 `false`
2. `EMAIL_PATTERN.matcher(email).matches()` — 正则 `^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$`（忽略大小写）
3. 匹配 → `true`，否则 `false`

---

## 14. 模型/枚举/DTO/接口

### 14.1 模型

**`ClientInfo`** — `record ClientInfo(String ip, String userAgent)`
- 纯数据载体，无方法

**`IdentifierType`** — `enum { PHONE, EMAIL }`
- `fromString(String value)`：将字符串转为枚举
  - `"phone"` / `"mobile"` → `PHONE`
  - `"email"` → `EMAIL`
  - 其他 → `IllegalArgumentException`

**`LoginLog`** — Lombok `@Data @Builder`
- 字段：id, userId, identifier, channel, ip, userAgent, status, createdAt

### 14.2 枚举

**`VerificationScene`** — `enum { REGISTER, LOGIN, RESET_PASSWORD }`

**`VerificationCodeStatus`** — `enum { SUCCESS, NOT_FOUND, EXPIRED, MISMATCH, TOO_MANY_ATTEMPTS }`

### 14.3 DTO（全部为 Java record，无方法）

| DTO | 字段 | 用途 |
|-----|------|------|
| `SendCodeRequest` | scene, identifierType, identifier | 发送验证码请求 |
| `SendCodeResponse` | identifier, scene, expireSeconds | 发送验证码响应 |
| `RegisterRequest` | identifierType, identifier, code, password, agreeTerms | 注册请求 |
| `LoginRequest` | identifierType, identifier, code, password | 登录请求 |
| `TokenRefreshRequest` | refreshToken | 刷新令牌请求 |
| `LogoutRequest` | refreshToken | 登出请求 |
| `PasswordResetRequest` | identifierType, identifier, code, newPassword | 重置密码请求 |
| `AuthResponse` | user(AuthUserResponse), token(TokenResponse) | 认证响应 |
| `AuthUserResponse` | id, nickname, avatar, phone, zhId, birthday, school, bio, gender, tagJson | 用户信息 |
| `TokenResponse` | accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt | 令牌信息 |

### 14.4 结果 record

**`TokenPair`** — `record(accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, refreshTokenId)`
- 内部使用，封装签发的令牌对

**`SendCodeResult`** — `record(identifier, scene, expireSeconds)`
- 验证码发送结果

**`VerificationCheckResult`** — `record(status, attempts, maxAttempts)`
- `isSuccess()` → `status == SUCCESS`

### 14.5 接口

**`RefreshTokenStore`** — 刷新令牌白名单接口
- `storeToken(userId, tokenId, ttl)` — 存储
- `isTokenValid(userId, tokenId)` — 校验
- `revokeToken(userId, tokenId)` — 撤销单个
- `revokeAll(userId)` — 撤销全部

**`VerificationCodeStore`** — 验证码存储接口
- `saveCode(scene, identifier, code, ttl, maxAttempts)` — 保存
- `verify(scene, identifier, code)` — 校验
- `invalidate(scene, identifier)` — 失效

**`CodeSender`** — 验证码发送接口
- `sendCode(scene, identifier, code, expireMinutes)` — 发送

**`LoginLogMapper`** — MyBatis Mapper
- `insert(LoginLog log)` — 插入审计日志

---

## 附录：函数调用关系总览

```
AuthController
├── sendCode()           → AuthService.sendCode()
├── register()           → AuthService.register()
├── login()              → AuthService.login()
├── refresh()            → AuthService.refresh()
├── logout()             → AuthService.logout()
├── resetPassword()      → AuthService.resetPassword()
├── me()                 → JwtService.extractUserId() + AuthService.me()
├── resolveClient()      → extractClientIp() + getHeader("User-Agent")
└── extractClientIp()    → X-Forwarded-For / X-Real-IP / getRemoteAddr()

AuthService
├── sendCode()           → validateIdentifier() + normalizeIdentifier() + identifierExists()
│                          + VerificationService.sendCode()
├── register()           → validateIdentifier() + normalizeIdentifier() + identifierExists()
│                          + VerificationService.verify() + ensureVerificationSuccess()
│                          + UserService.createUser() + JwtService.issueTokenPair()
│                          + storeRefreshToken() + LoginLogService.record()
├── login()              → validateIdentifier() + normalizeIdentifier() + findUserByIdentifier()
│                          + PasswordEncoder.matches() / VerificationService.verify()
│                          + JwtService.issueTokenPair() + storeRefreshToken()
│                          + LoginLogService.record()
├── refresh()            → decodeRefreshToken() + JwtService.extractTokenType/UserId/TokenId()
│                          + RefreshTokenStore.isTokenValid() + findUserById()
│                          + JwtService.issueTokenPair() + RefreshTokenStore.revokeToken()
│                          + storeRefreshToken()
├── logout()             → decodeRefreshTokenSafely() + JwtService.extractTokenType/UserId/TokenId()
│                          + RefreshTokenStore.revokeToken()
├── resetPassword()      → validateIdentifier() + validatePassword() + normalizeIdentifier()
│                          + findUserByIdentifier() + VerificationService.verify()
│                          + ensureVerificationSuccess() + PasswordEncoder.encode()
│                          + UserService.updatePassword() + RefreshTokenStore.revokeAll()
├── me()                 → findUserById() + mapUser()
├── ensureVerificationSuccess() — 状态 → 异常映射
├── validateIdentifier() → IdentifierValidator.isValidPhone/Email()
├── validatePassword()   — 非空 + 长度 + 字母数字
├── identifierExists()   → UserService.existsByPhone/Email()
├── findUserByIdentifier() → UserService.findByPhone/Email()
├── findUserById()       → UserService.findById()
├── normalizeIdentifier() — trim + lowercase
├── storeRefreshToken()  → RefreshTokenStore.storeToken()
├── mapUser()            — User → AuthUserResponse
├── mapToken()           — TokenPair → TokenResponse
├── generateNickname()   — "知光用户" + UUID[0:8]
├── decodeRefreshToken() → JwtService.decode() + 异常包装
└── decodeRefreshTokenSafely() → JwtService.decode() + Optional 包装

JwtService
├── issueTokenPair()     → encodeToken() + encodeRefreshToken()
├── decode()             → JwtDecoder.decode()
├── encodeToken()        → JwtClaimsSet + JwtEncoder.encode()
├── encodeRefreshToken() → JwtClaimsSet + JwtEncoder.encode()
├── extractUserId()      → claims.get("uid")
├── extractTokenType()   → claims.get("token_type")
└── extractTokenId()     → jwt.getId()

VerificationService
├── sendCode()           → enforceSendInterval() + enforceDailyLimit()
│                          + generateNumericCode() + CodeStore.saveCode() + CodeSender.sendCode()
├── verify()             → CodeStore.verify()
├── invalidate()         → CodeStore.invalidate()
├── enforceSendInterval() → Redis GET/SET
├── enforceDailyLimit()  → Redis INCR + EXPIRE
└── generateNumericCode() → SecureRandom

RedisVerificationCodeStore
├── saveCode()           → HSET + EXPIRE
├── verify()             → HGETALL + 比对 + HINCRBY + DEL/EXPIRE
├── invalidate()         → DEL
├── buildKey()           → 字符串格式化
└── parseInt()           → 安全解析

RedisRefreshTokenStore
├── storeToken()         → SET + EX
├── isTokenValid()       → GET + 比对
├── revokeToken()        → DEL
├── revokeAll()          → KEYS + DEL
└── key()                → 字符串格式化

LoginLogService
└── record()             → 构建 LoginLog + LoginLogMapper.insert()

AuthConfiguration
├── passwordEncoder()    → new BCryptPasswordEncoder(strength)
├── jwtEncoder()         → PemUtils + RSAKey + NimbusJwtEncoder
└── jwtDecoder()         → PemUtils + NimbusJwtDecoder

PemUtils
├── readPrivateKey()     → readResource() + strip PEM + Base64 + PKCS8EncodedKeySpec
├── readPublicKey()      → readResource() + strip PEM + Base64 + X509EncodedKeySpec
└── readResource()       → InputStream.readAllBytes()

LoggingCodeSender
└── sendCode()           → log.info()

IdentifierValidator
├── isValidPhone()       → Pattern.matches(^1\d{10}$)
└── isValidEmail()       → Pattern.matches(标准邮箱正则)
```

---

*文档生成时间：2026-05-02 | 覆盖 auth 模块 22 个文件、70+ 个函数*
