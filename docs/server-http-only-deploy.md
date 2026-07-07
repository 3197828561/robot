# 现有阿里云服务器只补 HTTP API（不重复部署 EMQX）

适用当前服务器状态：

- ECS：`47.103.157.213`
- 系统：Ubuntu 24.04.4 LTS
- 用户：`robot`
- 项目目录：`/opt/robot-platform`
- EMQX 已存在并占用 `1883`，不要再运行包含 EMQX 的 Compose

## 1. 为什么要用 HTTP-only

仓库默认 `deploy/docker-compose.yml` 包含：EMQX + PostgreSQL + FastAPI + Nginx。

你的服务器已经部署了 EMQX 5.8.6，如果直接运行默认 Compose，可能出现：

- 1883 端口冲突
- 出现第二套 EMQX，App 和机器人连到不同 Broker
- 已创建的 `app_user_001` / `robot_device_001` 不在新 Broker 中

因此当前阶段只运行：

- PostgreSQL
- FastAPI HTTP API
- Nginx

对应文件：`deploy/docker-compose.http-only.yml`

## 2. 上传/同步代码到服务器

推荐使用 Git。若服务器还没有仓库代码：

```bash
ssh robot@47.103.157.213
cd /opt/robot-platform
git clone https://github.com/3197828561/robot.git app
cd app
```

如果已经 clone 过：

```bash
ssh robot@47.103.157.213
cd /opt/robot-platform/app
git pull
```

如果服务器无法访问 GitHub，可在本机压缩 `deploy/` 上传到 `/opt/robot-platform/app/deploy`。

## 3. 配置 HTTP API 环境变量

```bash
cd /opt/robot-platform/app/deploy
cp .env.example .env
vim .env
```

建议 `.env` 内容：

```properties
PUBLIC_HOST=47.103.157.213
POSTGRES_USER=vgsolar
POSTGRES_PASSWORD=请改成强密码
POSTGRES_DB=vgsolar
JWT_SECRET=请改成至少32位随机字符串

# 这两项留给默认完整 Compose 使用；HTTP-only 不读取，但保留也没关系
EMQX_APP_USERNAME=app_user_001
EMQX_APP_PASSWORD=不要写入Git的MQTT密码

API_BOOTSTRAP_EMAIL=test@vgsolar.local
API_BOOTSTRAP_PASSWORD=请改成联调登录密码
```

生成随机字符串：

```bash
openssl rand -base64 32
```

## 4. 启动 HTTP API

```bash
cd /opt/robot-platform/app/deploy
docker compose -f docker-compose.http-only.yml --env-file .env up -d --build
```

如果构建卡在 `pip install`，并出现 `files.pythonhosted.org` 超时，说明服务器访问国外 PyPI 太慢。当前 `deploy/api/Dockerfile` 已默认使用阿里云 PyPI 镜像，可先拉取最新代码后重新构建：

```bash
cd /opt/robot-platform/app
git pull
cd deploy
docker compose -f docker-compose.http-only.yml --env-file .env build --no-cache api
docker compose -f docker-compose.http-only.yml --env-file .env up -d
```

查看状态：

```bash
docker compose -f docker-compose.http-only.yml ps
docker logs -f vgsolar-api
docker logs -f vgsolar-nginx
```

如果访问 `/health` 返回 `502 Bad Gateway`，说明 Nginx 已启动，但后端 API 暂时不可用。先看 API 日志：

```bash
docker logs --tail=200 vgsolar-api
docker logs --tail=100 vgsolar-nginx
```

当前后端已避免把 PostgreSQL 密码直接拼进 `DATABASE_URL`，因此 `.env` 里的 `POSTGRES_PASSWORD` 即使包含 `@`、`:`、`#` 等特殊字符，也不会破坏数据库连接串。更新代码后重新构建并启动：

```bash
cd /opt/robot-platform/app
git pull
cd deploy
docker compose -f docker-compose.http-only.yml --env-file .env up -d --build
docker compose -f docker-compose.http-only.yml ps
curl http://127.0.0.1/health
```

如果执行 `up -d --build` 时出现 `dependency api failed to start`，说明 API 容器启动后没有通过健康检查。优先看日志：

```bash
docker logs --tail=200 vgsolar-api
docker compose -f docker-compose.http-only.yml --env-file .env ps
```

最常见原因是 `.env` 里的 `POSTGRES_PASSWORD` 改过，但服务器上已有的 `postgres_data` volume 仍然保留旧数据库密码。PostgreSQL 官方镜像只会在第一次初始化空数据库时读取 `POSTGRES_PASSWORD`，之后修改 `.env` 不会自动改数据库内部密码。

当前是联调环境、数据库里没有重要生产数据时，最简单修复是删除旧 volume 后重建：

```bash
cd /opt/robot-platform/app/deploy
docker compose -f docker-compose.http-only.yml --env-file .env down -v
docker compose -f docker-compose.http-only.yml --env-file .env up -d --build
docker compose -f docker-compose.http-only.yml --env-file .env ps
curl http://127.0.0.1/health
```

注意：`down -v` 会删除本 compose 创建的 PostgreSQL 数据卷，联调样例数据会重新初始化；如果未来已有正式数据，不要直接执行，改用 `ALTER USER` 重置数据库密码。

## 5. 验证 HTTP

服务器本机：

```bash
curl http://127.0.0.1/health
```

本地电脑：

```powershell
curl http://47.103.157.213/health
```

登录接口：

```bash
curl -X POST http://47.103.157.213/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.local","password":"你的API_BOOTSTRAP_PASSWORD"}'
```

返回中应有 `access_token`。

## 6. App local.properties

不要提交 `local.properties`，只在你电脑本机填写：

```properties
api.base.url=http://47.103.157.213/api
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=私下保存的App MQTT密码
```

然后 Android Studio：Sync Project with Gradle Files → Run。

## 7. 与硬件组确认

请硬件组确认：

```text
Broker: 47.103.157.213:1883
Robot MQTT Username: robot_device_001
Device ID: rk3588（如不是，请提供真实值）
Telemetry topic: device/{device_id}/telemetry
Remote topic: device/{device_id}/remote
Cmd topic: device/{device_id}/cmd
```

## 8. 不要做的事

- 不要在服务器上运行 `docker compose up -d` 默认文件，除非你决定重建 EMQX
- 不要开放 EMQX Dashboard 18083 到 `0.0.0.0/0`
- 不要把 `.env`、`local.properties`、含密码 PDF 提交 Git
- 不要把 `device_id` 和 MQTT 用户名混用；`robot_device_001` 是账号，不一定是设备 ID
