# 阿里云服务器部署指南（从零到联调上线）

本文档面向 **第一次配置阿里云 ECS** 的场景，手把手完成：

- **MQTT Broker**（EMQX 5，对接 `cloud_comm` 与 Android App）
- **HTTP API**（FastAPI，登录 / 设备 / 作业记录 / 固件 / WiFi）
- **PostgreSQL**（用户与业务数据）
- **Nginx**（反向代理与 HTTPS）

完成后，你将得到 App 与硬件组联调所需的全部地址与账号信息。

---

## 目录

1. [整体架构](#1-整体架构)
2. [你需要准备什么](#2-你需要准备什么)
3. [云服务器上要下载/安装的内容清单](#3-云服务器上要下载安装的内容清单)
4. [第一步：购买并初始化 ECS](#4-第一步购买并初始化-ecs)
5. [第二步：SSH 登录服务器](#5-第二步ssh-登录服务器)
6. [第三步：系统初始化](#6-第三步系统初始化)
7. [第四步：安装 Docker 与 Docker Compose](#7-第四步安装-docker-与-docker-compose)
8. [第五步：上传部署文件到服务器](#8-第五步上传部署文件到服务器)
9. [第六步：配置环境变量并启动全部服务](#9-第六步配置环境变量并启动全部服务)
10. [第七步：初始化 EMQX（MQTT 账号）](#10-第七步初始化-emqxmqtt-账号)
11. [第八步：初始化数据库与 API 测试账号](#11-第八步初始化数据库与-api-测试账号)
12. [第九步：配置 Nginx 与 HTTPS（可选）](#12-第九步配置-nginx-与-https可选)
13. [第十步：验证清单（必须全部通过）](#13-第十步验证清单必须全部通过)
14. [联调信息表（填好后发给 App / 硬件组）](#14-联调信息表填好后发给-app--硬件组)
15. [与 cloud_comm 协议对齐说明](#15-与-cloud_comm-协议对齐说明)
16. [常见问题排查](#16-常见问题排查)
17. [安全建议（联调期 vs 上线期）](#17-安全建议联调期-vs-上线期)

---

## 1. 整体架构

```text
                    ┌─────────────────────────────────────┐
  Android App ─────►│  阿里云 ECS（Ubuntu 22.04）          │
  硬件 cloud_comm ─►│                                     │
                    │  Nginx :80 / :443                   │
                    │    ├─ /api/*  → FastAPI :8000       │
                    │                                     │
                    │  EMQX :1883 (MQTT)                  │
                    │    ├─ device/{id}/telemetry         │
                    │    ├─ device/{id}/cmd / remote ...  │
                    │                                     │
                    │  PostgreSQL :5432（内网）            │
                    └─────────────────────────────────────┘
```

| 组件 | 端口 | 用途 |
|------|------|------|
| EMQX | 1883 | MQTT，App 与机器人通信 |
| EMQX Dashboard | 18083 | Web 管理台（仅联调期开放，上线应限制 IP） |
| FastAPI | 8000 | HTTP REST API（Nginx 反代后对外） |
| Nginx | 80 / 443 | 对外 HTTP/HTTPS 入口 |
| PostgreSQL | 5432 | 仅 Docker 内网，不对外暴露 |

---

## 2. 你需要准备什么

| 项目 | 说明 |
|------|------|
| 阿里云账号 | [https://www.aliyun.com](https://www.aliyun.com) 注册并完成实名认证 |
| ECS 实例 | 建议 **2 核 4 GB** 起，系统盘 **40 GB+**，系统选 **Ubuntu 22.04 64 位** |
| 弹性公网 IP | 购买 ECS 时勾选，或事后绑定 |
| 本地电脑 | Windows / macOS，能 SSH 到服务器 |
| （可选）域名 | 有域名可配 HTTPS；没有域名可先用 `http://公网IP/api` |
| 本仓库 | 含 `deploy/` 目录下的 Docker Compose 与 API 骨架 |

**预估费用（参考）**：按量或包月 2C4G 约几十到一百多元/月，以阿里云控制台为准。

---

## 3. 云服务器上要下载/安装的内容清单

登录 ECS 后，将通过命令行 **自动下载** 以下内容（无需手动逐个找下载链接）：

| 类别 | 名称 | 获取方式 | 用途 |
|------|------|----------|------|
| 系统包 | `curl`、`git`、`ca-certificates` 等 | `apt install` | 基础工具 |
| 容器运行时 | **Docker CE** | 官方脚本 / 阿里云镜像源 | 运行 EMQX、API、数据库 |
| 编排工具 | **Docker Compose v2** | 随 Docker 插件或独立安装 | 一键启动多容器 |
| 镜像 | `emqx/emqx:5.8.3` | `docker pull`（Docker Hub，国内可能较慢） | MQTT Broker |
| 镜像 | `postgres:16-alpine` | `docker pull` | 关系型数据库 |
| 镜像 | `nginx:1.27-alpine` | `docker pull` | 反向代理 |
| 镜像 | 自建 `vgsolar-api` | `docker compose build` | HTTP API 服务 |
| Python 依赖 | FastAPI、Uvicorn、SQLAlchemy 等 | Dockerfile 内 `pip install` | API 运行时 |

> **说明**：不需要在服务器上单独安装 Java、Node.js、EMQX 安装包；全部通过 Docker 镜像交付。

---

## 4. 第一步：购买并初始化 ECS

### 4.1 创建实例（控制台操作）

1. 登录 [阿里云 ECS 控制台](https://ecs.console.aliyun.com/)
2. **创建实例** → 选择地域（建议与团队就近，如华东）
3. 镜像：**Ubuntu 22.04 64 位**
4. 规格：**2 vCPU / 4 GiB** 或以上
5. 系统盘：**40 GiB** 或以上
6. 网络：默认 VPC 即可；**分配公网 IPv4 地址**
7. 安全组：先选默认，下一节单独配置规则
8. 登录方式：推荐 **密钥对**（更安全）；也可先用 **密码**
9. 创建完成后，在实例列表记下 **公网 IP**，下文记为 `YOUR_ECS_IP`

### 4.2 配置安全组（入方向规则）

在 ECS → **安全组** → **配置规则** → **入方向**，添加：

| 协议 | 端口 | 授权对象 | 说明 |
|------|------|----------|------|
| TCP | 22 | 你的办公网 IP/32 | SSH（不要写 0.0.0.0/0 除非临时调试） |
| TCP | 80 | 0.0.0.0/0 | HTTP |
| TCP | 443 | 0.0.0.0/0 | HTTPS |
| TCP | 1883 | 0.0.0.0/0 | MQTT（联调期；上线建议改白名单） |
| TCP | 18083 | 你的办公网 IP/32 | EMQX 控制台（仅管理员） |

> 硬件组机器人若在公网外仅内网访问，1883 可改为 **硬件组出口 IP + App 测试机 IP** 的白名单。

---

## 5. 第二步：SSH 登录服务器

在 **本地 PowerShell**（Windows）或终端（macOS/Linux）执行：

```bash
# 密钥登录（推荐，把路径换成你的 .pem 文件）
ssh -i "C:\path\to\your-key.pem" root@YOUR_ECS_IP

# 密码登录
ssh root@YOUR_ECS_IP
```

首次连接提示 `Are you sure you want to continue connecting?` 输入 `yes`。

登录成功后，提示符类似：`root@iZxxxx:~#`

---

## 6. 第三步：系统初始化

以下命令 **均在 ECS 上以 root 或 sudo 用户执行**。

### 6.1 更新系统并安装基础工具

```bash
export DEBIAN_FRONTEND=noninteractive

apt-get update -y
apt-get upgrade -y

apt-get install -y \
  ca-certificates \
  curl \
  gnupg \
  lsb-release \
  git \
  vim \
  unzip \
  htop \
  net-tools
```

### 6.2 设置时区（可选，建议上海）

```bash
timedatectl set-timezone Asia/Shanghai
date
```

### 6.3 创建部署目录

```bash
mkdir -p /opt/vgsolar
cd /opt/vgsolar
```

---

## 7. 第四步：安装 Docker 与 Docker Compose

### 7.1 卸载旧版本（若有）

```bash
apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true
```

### 7.2 添加 Docker 官方 GPG 与仓库（Ubuntu 22.04）

```bash
install -m 0755 -d /etc/apt/keyrings

curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | tee /etc/apt/sources.list.d/docker.list > /dev/null

apt-get update -y
```

> 若 `download.docker.com` 很慢，可改用 [阿里云 Docker CE 镜像源文档](https://developer.aliyun.com/mirror/docker-ce) 替换上面的 `curl` 与 `echo` 步骤。

### 7.3 安装 Docker Engine 与 Compose 插件

```bash
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable docker
systemctl start docker

docker --version
docker compose version
```

预期输出示例：

```text
Docker version 27.x.x
Docker Compose version v2.x.x
```

### 7.4 验证 Docker 是否正常

```bash
docker run --rm hello-world
```

看到 `Hello from Docker!` 即成功。

### 7.5 预拉取镜像（可选，减少首次启动等待）

```bash
docker pull emqx/emqx:5.8.3
docker pull postgres:16-alpine
docker pull nginx:1.27-alpine
docker pull python:3.12-slim
```

---

## 8. 第五步：上传部署文件到服务器

本仓库 `deploy/` 目录包含一键部署所需全部文件。任选 **方式 A（Git）** 或 **方式 B（SCP）**。

### 方式 A：Git 克隆（推荐）

在 ECS 上执行：

```bash
cd /opt/vgsolar

# 若仓库为私有，需先在 GitHub 配置 Deploy Key 或使用 HTTPS + Token
git clone https://github.com/3197828561/robot.git app-src

cp -r app-src/deploy ./deploy
cd /opt/vgsolar/deploy
ls -la
```

应能看到：`docker-compose.yml`、`.env.example`、`nginx/`、`api/` 等。

### 方式 B：本地上传（Windows PowerShell）

在 **本地电脑**（项目根目录）执行：

```powershell
scp -r "c:\Users\31978\AndroidStudioProjects\project01\deploy" root@YOUR_ECS_IP:/opt/vgsolar/
```

然后在 ECS 上：

```bash
cd /opt/vgsolar/deploy
ls -la
```

---

## 9. 第六步：配置环境变量并启动全部服务

### 9.1 复制并编辑环境变量

```bash
cd /opt/vgsolar/deploy

cp .env.example .env
vim .env
```

**必须修改** 的项（把示例值换成你的）：

```bash
# ECS 公网 IP 或域名（不要带 http://）
PUBLIC_HOST=YOUR_ECS_IP

# PostgreSQL
POSTGRES_USER=vgsolar
POSTGRES_PASSWORD=请改成强密码至少16位
POSTGRES_DB=vgsolar

# JWT（API 登录 Token 签名，随机长字符串）
JWT_SECRET=请改成随机字符串至少32位

# EMQX 默认应用账号（App 与 cloud_comm 连接 MQTT 时使用）
EMQX_APP_USERNAME=vgsolar_app
EMQX_APP_PASSWORD=请改成强密码

# 首个 API 测试用户（联调登录用）
API_BOOTSTRAP_EMAIL=test@vgsolar.com
API_BOOTSTRAP_PASSWORD=Test123456!
```

生成随机密码示例：

```bash
openssl rand -base64 24
```

### 9.2 构建并启动所有容器

```bash
cd /opt/vgsolar/deploy

docker compose pull
docker compose build --no-cache
docker compose up -d

docker compose ps
```

预期所有服务 `STATE` 为 `running`：

```text
NAME              STATUS
vgsolar-emqx      running
vgsolar-postgres  running
vgsolar-api       running
vgsolar-nginx     running
```

### 9.3 查看日志（排错时用）

```bash
# 全部服务
docker compose logs -f --tail=100

# 单个服务
docker compose logs -f emqx
docker compose logs -f api
docker compose logs -f nginx
docker compose logs -f postgres
```

### 9.4 重启 / 停止 / 更新

```bash
cd /opt/vgsolar/deploy

# 修改配置后重启
docker compose down
docker compose up -d

# 仅重启 API
docker compose restart api

# 拉代码后重新构建 API
docker compose build api
docker compose up -d api
```

---

## 10. 第七步：初始化 EMQX（MQTT 账号）

### 10.1 打开 EMQX 控制台

浏览器访问（需安全组放行 18083，且建议仅你的 IP）：

```text
http://YOUR_ECS_IP:18083
```

默认账号（EMQX 5 首次安装）：

- 用户名：`admin`
- 密码：`public`

**登录后立即修改 admin 密码**：左侧 **系统设置** → **用户**。

### 10.2 创建 App / 机器人共用的 MQTT 用户

控制台路径：**访问控制** → **认证** → **密码认证** → **用户管理** → **添加**。

| 字段 | 值 |
|------|-----|
| 用户 ID | 与 `.env` 中 `EMQX_APP_USERNAME` 一致，如 `vgsolar_app` |
| 密码 | 与 `.env` 中 `EMQX_APP_PASSWORD` 一致 |

保存后，Android App 与 `cloud_comm` 均使用该账号连接 Broker。

### 10.3 确认 MQTT 端口监听

在 ECS 上：

```bash
docker exec vgsolar-emqx emqx ctl listeners
```

或：

```bash
ss -tlnp | grep 1883
```

### 10.4 命令行快速测试 MQTT（在 ECS 上）

安装 Mosquitto 客户端（仅测试用）：

```bash
apt-get install -y mosquitto-clients
```

**订阅** telemetry（另开一个 SSH 窗口）：

```bash
mosquitto_sub -h 127.0.0.1 -p 1883 \
  -u vgsolar_app -P '你的EMQX_APP_PASSWORD' \
  -t 'device/rk3588/telemetry' -v
```

**发布** 模拟 telemetry：

```bash
mosquitto_pub -h 127.0.0.1 -p 1883 \
  -u vgsolar_app -P '你的EMQX_APP_PASSWORD' \
  -t 'device/rk3588/telemetry' \
  -m '{"schema":"vgsolar.cloud_comm.v1","type":"telemetry","device_id":"rk3588","timestamp_ms":1715000000000,"status":{"battery_percent":85.0,"fault_code":0,"fcu_connected":true}}'
```

订阅窗口应收到 JSON。

---

## 11. 第八步：初始化数据库与 API 测试账号

API 容器 **首次启动** 会自动：

1. 创建 PostgreSQL 表
2. 写入 `.env` 中的 bootstrap 测试用户
3. 写入一台示例设备 `rk3588`

### 11.1 健康检查

```bash
curl -s http://127.0.0.1:8000/health
```

预期：

```json
{"status":"ok"}
```

经 Nginx：

```bash
curl -s http://YOUR_ECS_IP/health
curl -s http://YOUR_ECS_IP/api/health
```

### 11.2 登录获取 Token

```bash
curl -s -X POST http://YOUR_ECS_IP/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.com","password":"Test123456!"}'
```

预期返回含 `access_token` 的 JSON。记下 token，下文记为 `YOUR_TOKEN`。

### 11.3 获取设备列表

```bash
curl -s http://YOUR_ECS_IP/api/devices \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 11.4 其他 HTTP 接口（联调前可先测通）

```bash
# 作业记录
curl -s "http://YOUR_ECS_IP/api/jobs?device_id=rk3588" \
  -H "Authorization: Bearer YOUR_TOKEN"

# 最新固件
curl -s "http://YOUR_ECS_IP/api/firmware/latest?device_id=rk3588" \
  -H "Authorization: Bearer YOUR_TOKEN"

# WiFi 配置读取
curl -s http://YOUR_ECS_IP/api/devices/rk3588/wifi \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

## 12. 第九步：配置 Nginx 与 HTTPS（可选）

联调阶段可只用 `http://YOUR_ECS_IP/api`。有域名时建议配置 HTTPS。

### 12.1 域名解析

在域名 DNS 控制台添加 **A 记录**：

```text
api.yourdomain.com  →  YOUR_ECS_IP
```

### 12.2 安装 Certbot 并申请证书

```bash
apt-get install -y certbot

# 先停 nginx 容器释放 80 端口
cd /opt/vgsolar/deploy
docker compose stop nginx

certbot certonly --standalone -d api.yourdomain.com \
  --non-interactive --agree-tos -m your-email@example.com

docker compose start nginx
```

证书路径：

```text
/etc/letsencrypt/live/api.yourdomain.com/fullchain.pem
/etc/letsencrypt/live/api.yourdomain.com/privkey.pem
```

### 12.3 启用 HTTPS 配置

编辑 `deploy/nginx/conf.d/default.conf`，取消 HTTPS `server` 块注释，并挂载证书卷（见 `deploy/docker-compose.yml` 中注释说明），然后：

```bash
cd /opt/vgsolar/deploy
docker compose up -d nginx
```

验证：

```bash
curl -s https://api.yourdomain.com/api/health
```

---

## 13. 第十步：验证清单（必须全部通过）

在 ECS 或本地电脑逐项执行：

| # | 检查项 | 命令 / 操作 | 预期 |
|---|--------|-------------|------|
| 1 | Docker 服务运行 | `docker compose ps` | 4 个容器 running |
| 2 | API 健康 | `curl http://YOUR_ECS_IP/api/health` | `{"status":"ok"}` |
| 3 | 登录 | `POST /api/auth/login` | 返回 `access_token` |
| 4 | 设备列表 | `GET /api/devices` + Bearer | 含 `rk3588` |
| 5 | MQTT 端口 | `telnet YOUR_ECS_IP 1883` 或 MQTTX 连接 | 连接成功 |
| 6 | MQTT 鉴权 | MQTTX 用 `vgsolar_app` 连接 | 无 `Not authorized` |
| 7 | 订阅 telemetry | MQTTX 订阅 `device/rk3588/telemetry` | 能收到消息 |
| 8 | 发布 cmd | MQTTX 发布到 `device/rk3588/cmd` | 无 Error |
| 9 | EMQX 控制台 | 浏览器 `:18083` | 可登录 |
| 10 | 硬件 cloud_comm | 机器人连同一 Broker | Dashboard 可见客户端 |

**推荐桌面工具**：[MQTTX](https://mqttx.app/)（Windows/macOS 图形化测试 MQTT）。

MQTTX 连接参数示例：

| 字段 | 值 |
|------|-----|
| Host | `YOUR_ECS_IP` |
| Port | `1883` |
| Username | `vgsolar_app` |
| Password | `.env` 中的 `EMQX_APP_PASSWORD` |
| Client ID | 任意唯一字符串 |

---

## 14. 联调信息表（填好后发给 App / 硬件组）

复制下表，部署完成后填写，发到项目群：

```text
========== 光伏机器人联调环境 ==========
部署日期：
ECS 公网 IP：YOUR_ECS_IP
（可选）API 域名：https://api.yourdomain.com

--- MQTT（cloud_comm / App 共用）---
MQTT Broker：tcp://YOUR_ECS_IP:1883
MQTT 用户名：vgsolar_app
MQTT 密码：********（私下发送，勿贴群）
设备 ID：rk3588          ← 与 cloud_comm 配置一致

订阅主题（设备→云）：
  device/rk3588/telemetry
  device/rk3588/event
  device/rk3588/connection

发布主题（云→设备）：
  device/rk3588/cmd
  device/rk3588/remote
  device/rk3588/config

--- HTTP API（App 账号 / 业务）---
API Base URL：http://YOUR_ECS_IP/api
测试账号：test@vgsolar.com
测试密码：********（私下发送）

--- 硬件组 cloud_comm 配置参考 ---
Broker URL：mqtt://YOUR_ECS_IP:1883
Username：vgsolar_app
Password：********
Device ID：rk3588

--- 安全组已放行 ---
22 / 80 / 443 / 1883（18083 仅管理员 IP）

========== END ==========
```

**Android App 侧** 后续写入 `local.properties`（勿提交 Git）：

```properties
mqtt.host=YOUR_ECS_IP
mqtt.port=1883
mqtt.username=vgsolar_app
mqtt.password=你的密码
api.base.url=http://YOUR_ECS_IP/api
```

---

## 15. 与 cloud_comm 协议对齐说明

MQTT 主题与 JSON 格式以仓库内 `API.md`（`vgsolar.cloud_comm.v1`）为准，**不是** Demo 里的 `solarbot/robot/*`。

| 方向 | 主题 | 说明 |
|------|------|------|
| 设备 → App | `device/{device_id}/telemetry` | 约 1s 周期状态 |
| 设备 → App | `device/{device_id}/event` | 指令/参数反馈 |
| 设备 → App | `device/{device_id}/connection` | 在线/离线 |
| App → 设备 | `device/{device_id}/cmd` | start/stop/estop 等 |
| App → 设备 | `device/{device_id}/remote` | 线速度/角速度遥控 |
| App → 设备 | `device/{device_id}/config` | 参数下发 |

硬件组需在机器人侧 `cloud_comm` 配置 **相同的 Broker 地址、MQTT 账号、device_id**。

---

## 16. 常见问题排查

### 16.1 `docker compose up` 后 API 起不来

```bash
docker compose logs api
docker compose logs postgres
```

常见原因：`.env` 中数据库密码含特殊字符未转义；PostgreSQL 尚未 ready——可 `docker compose restart api`。

### 16.2 外网访问不了 80 / 1883

1. 检查 **阿里云安全组** 入方向是否放行
2. 检查 ECS 内 **防火墙**（Ubuntu 默认 ufw 关闭；若开启需 `ufw allow 80` 等）

```bash
ufw status
```

### 16.3 MQTTX 连接被拒绝 / Not authorized

- 确认 EMQX 已创建用户 `vgsolar_app`
- 用户名密码与 `.env` 一致
- 确认连接的是 **1883** 而非 8883（未配 TLS 时）

### 16.4 `docker pull` 很慢或超时

在 `/etc/docker/daemon.json` 配置镜像加速（阿里云容器镜像服务可免费获取专属加速地址）：

```bash
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://你的ID.mirror.aliyuncs.com"
  ]
}
EOF

systemctl daemon-reload
systemctl restart docker
```

然后重新 `docker compose pull`。

### 16.5 Git clone 失败

私有仓库需 Personal Access Token：

```bash
git clone https://<TOKEN>@github.com/3197828561/robot.git
```

或使用 SSH Deploy Key。

---

## 17. 安全建议（联调期 vs 上线期）

| 联调期（本周） | 上线期 |
|----------------|--------|
| 1883 可对 0.0.0.0/0 开放 | 改 **8883 + TLS** 或 IP 白名单 + VPN |
| HTTP 明文 API | 必须 **HTTPS** |
| EMQX 默认端口 18083 限制管理员 IP | 关闭公网访问或走 SSH 隧道 |
| 使用测试密码 | 强密码 + 定期轮换 |
| PostgreSQL 不对外 | 保持仅 Docker 内网 |

---

## 附录 A：完整命令速查（复制执行顺序）

```bash
# === 在 ECS 上按顺序执行 ===

# 1. 系统初始化
apt-get update -y && apt-get upgrade -y
apt-get install -y ca-certificates curl gnupg lsb-release git vim unzip htop net-tools
timedatectl set-timezone Asia/Shanghai
mkdir -p /opt/vgsolar && cd /opt/vgsolar

# 2. 安装 Docker
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker && systemctl start docker

# 3. 获取部署文件（Git 方式示例）
git clone https://github.com/3197828561/robot.git app-src
cp -r app-src/deploy ./deploy
cd /opt/vgsolar/deploy

# 4. 配置并启动
cp .env.example .env
vim .env   # 修改密码与 IP
docker compose pull
docker compose build --no-cache
docker compose up -d
docker compose ps

# 5. 验证
curl -s http://127.0.0.1:8000/health
curl -s -X POST http://127.0.0.1/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.com","password":"Test123456!"}'
```

---

## 附录 B：目录结构说明

```text
deploy/
├── docker-compose.yml    # 一键编排 EMQX + Postgres + API + Nginx
├── .env.example          # 环境变量模板（复制为 .env）
├── nginx/
│   └── conf.d/
│       └── default.conf  # 反代 /api → api:8000
└── api/
    ├── Dockerfile
    ├── requirements.txt
    └── app/
        └── main.py       # FastAPI 最小可联调实现
```

更新部署时，只需同步 `deploy/` 目录后在 ECS 执行 `docker compose build && docker compose up -d`。

---

**文档版本**：v1.0  
**适用项目**：光伏机器人 Android App + cloud_comm MQTT + 阿里云 ECS  
**维护**：与 `docs/openapi.yaml`（待编写）及 `API.md` 同步更新
