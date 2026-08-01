# 登录 HTTP 500：Passlib/Bcrypt 兼容修复说明

## 1. 故障现象

```text
POST /api/auth/login -> HTTP 500 Internal Server Error
GET /health -> HTTP 200
postgres/api -> healthy
nginx -> 正常代理到 API
```

数据库查询能够取得 `test@vgsolar.com` 用户，`password_hash` 是合法的 `$2b$` bcrypt 哈希。异常发生在 `main.py` 的 `verify_password()`：Passlib 加载 bcrypt 后端并执行密码比较时崩溃。

因此本故障与用户不存在、数据库连接、Nginx、服务器负载、地图接口和 mission 接口均无关。

## 2. 根因

原依赖只固定：

```text
passlib[bcrypt]==1.7.4
```

`bcrypt` 是未锁定的传递依赖。Docker 镜像重新构建时会安装当时可用的新版本，导致旧 Passlib 与新 bcrypt 后端行为不兼容。日志中的 `password cannot be longer than 72 bytes` 是后端兼容检测产生的次生异常，不表示实际登录密码超过 72 字节。

## 3. 修复

在 `deploy/api/requirements.txt` 中增加：

```text
bcrypt==4.0.1
```

保留 Passlib 1.7.4 和现有密码处理代码。数据库中的 `$2b$` 哈希与 bcrypt 4.0.1 兼容，不需要删除用户、修改密码或重建数据库。

新增 `deploy/api/tests/test_password_hashing.py`，验证：

- 新生成哈希以 `$2b$` 开头；
- 正确密码验证成功；
- 错误密码验证失败；
- 密码校验不再抛出后端初始化异常。

## 4. 服务器更新步骤

在服务器仓库目录执行，路径按实际部署目录调整：

```bash
git pull --ff-only origin main
cd deploy
docker compose -f docker-compose.http-only.yml config --quiet
docker compose -f docker-compose.http-only.yml build --no-cache api
docker compose -f docker-compose.http-only.yml up -d --no-deps api
```

禁止执行：

```bash
docker compose down -v
```

本次不需要重建 PostgreSQL，也不需要删除 `postgres_data`。

## 5. 部署验收

确认容器内版本：

```bash
docker exec vgsolar-api python -c \
'import importlib.metadata as m; print("passlib",m.version("passlib")); print("bcrypt",m.version("bcrypt"))'
```

期望：

```text
passlib 1.7.4
bcrypt 4.0.1
```

检查容器和健康接口：

```bash
docker compose -f docker-compose.http-only.yml ps
curl -i http://127.0.0.1/health
```

测试登录：

```bash
curl -i -X POST http://127.0.0.1/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@vgsolar.com","password":"<真实密码>"}'
```

正确密码期望 `200 OK` 并返回 `access_token/token_type/expires_in`；错误密码期望 `401 Unauthorized`，不能再返回 500。

最后检查日志：

```bash
docker logs --tail 100 vgsolar-api
```

不应再出现 bcrypt 后端加载、`__about__` 或 72 字节异常。
