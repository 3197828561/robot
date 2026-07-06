# 你的服务器部署实操教程（逐步复制命令）

> 适用服务器：`47.103.157.213`（华东2 上海）  
> 系统：Ubuntu 24.04 LTS | 配置：2 核 2G | 登录：`root@47.103.157.213`  
> 配套文件：项目内 `deploy/` 目录 + [`deploy-aliyun.md`](deploy-aliyun.md)

---

## 先搞懂：要装什么？不用装什么？

### 你需要在服务器上安装的（只有 2 类）

| 层级 | 装什么 | 作用 | 怎么装 |
|------|--------|------|--------|
| **系统工具** | git、curl、vim 等 | 拉代码、下载、编辑配置 | `apt install` 一条命令 |
| **Docker** | Docker Engine + Compose | 用容器跑所有业务服务 | 官方脚本 + `apt install` |

### 你不需要单独安装的（Docker 会自动下载镜像）

| 软件 | 说明 |
|------|------|
| EMQX | MQTT 消息服务器 → `docker pull emqx/emqx` |
| PostgreSQL | 数据库 → `docker pull postgres` |
| Nginx | Web 反向代理 → `docker pull nginx` |
| Python / FastAPI | HTTP API → `docker compose build` 自动构建 |
| Java、Node.js | **完全不需要** |

**一句话**：服务器上只装 **Docker**；EMQX、数据库、API、Nginx 全部由 Docker 容器运行。

---

## 部署前：阿里云控制台操作（不用 SSH）

在网页上做完，否则后面命令都连不上。

### 1. 配置安全组（放行端口）

1. 打开 [阿里云 ECS 控制台](https://ecs.console.aliyun.com/)
2. 找到实例 `i-uf617jofy09n9uywwwv51` → **网络与安全组** → **安全组** → **配置规则** → **入方向** → **手动添加**

添加以下规则：

| 协议 | 端口 | 授权对象 | 说明 |
|------|------|----------|------|
| TCP | 22 | 你的电脑公网 IP/32 | SSH（若不确定可先填 0.0.0.0/0，联调后改回） |
| TCP | 80 | 0.0.0.0/0 | HTTP API |
| TCP | 443 | 0.0.0.0/0 | HTTPS（以后用） |
| TCP | 1883 | 0.0.0.0/0 | MQTT（App + 机器人） |
| TCP | 18083 | 你的电脑 IP/32 | EMQX 管理网页 |

### 2. 确认 root 密码

若忘记密码：实例 → **重置密码** → 重启实例后生效。

---

## 第一步：登录服务器

任选一种方式。

### 方式 A：阿里云网页终端（最简单）

ECS 控制台 → **远程连接** → **Workbench 远程连接** → 用户名 `root` → 输入密码。

### 方式 B：本地 PowerShell

```powershell
ssh root@47.103.157.213
```

首次连接输入 `yes`，再输入 root 密码。

登录成功后，提示符类似：

```text
root@iZuf617jofy09n9uywwwv51:~#
```

---

## 第二步：系统初始化 + 增加 Swap（2G 内存必做）

你的机器只有 **2 GiB 内存**，跑 4 个容器可能吃紧。先加 **2G 交换分区** 防止 OOM 崩溃。

**在服务器上逐段复制执行：**

```bash
# --- 2.1 更新系统 ---
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get upgrade -y

# --- 2.2 安装基础工具 ---
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

# --- 2.3 设置时区为上海 ---
timedatectl set-timezone Asia/Shanghai
date

# --- 2.4 创建 2G Swap（2G 内存机器强烈建议）---
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
free -h

# --- 2.5 创建部署目录 ---
mkdir -p /opt/vgsolar
cd /opt/vgsolar
```

执行 `free -h` 后应看到 Swap 约 2.0Gi。

---

## 第三步：安装 Docker 与 Docker Compose

> 你的系统是 **Ubuntu 24.04**（代号 noble），与文档里 22.04 的安装命令兼容，Docker 会自动识别版本。

```bash
# --- 3.1 卸载旧版 Docker（若有，没有会忽略）---
apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

# --- 3.2 添加 Docker 官方 GPG 密钥 ---
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

# --- 3.3 添加 Docker 软件源（Ubuntu 24.04 自动识别为 noble）---
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | tee /etc/apt/sources.list.d/docker.list > /dev/null

# --- 3.4 安装 Docker ---
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# --- 3.5 设置开机自启并启动 ---
systemctl enable docker
systemctl start docker

# --- 3.6 验证安装 ---
docker --version
docker compose version
docker run --rm hello-world
```

**预期结果：**

- `Docker version 27.x` 或更高
- `Docker Compose version v2.x`
- 最后一行出现 `Hello from Docker!`

### 若 `curl download.docker.com` 很慢或超时

配置阿里云 Docker 镜像加速（登录 [容器镜像服务](https://cr.console.aliyun.com/) → 镜像工具 → 获取专属加速地址）：

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

---

## 第四步：把部署文件上传到服务器

项目 `deploy/` 文件夹在本地：

```text
c:\Users\31978\AndroidStudioProjects\project01\deploy\
```

### 方式 A：Windows PowerShell 上传（推荐，不依赖 GitHub）

**在你本地电脑**打开 PowerShell（不是服务器），执行：

```powershell
scp -r "c:\Users\31978\AndroidStudioProjects\project01\deploy" root@47.103.157.213:/opt/vgsolar/
```

输入 root 密码，等待上传完成。

### 方式 B：Git 克隆（需代码已推到 GitHub）

**在服务器上**执行：

```bash
cd /opt/vgsolar
git clone https://github.com/3197828561/robot.git app-src
cp -r app-src/deploy ./deploy
```

### 确认文件已到位

**在服务器上**执行：

```bash
cd /opt/vgsolar/deploy
ls -la
```

应看到：`docker-compose.yml`、`.env.example`、`nginx/`、`api/` 等。

---

## 第五步：配置环境变量

```bash
cd /opt/vgsolar/deploy
cp .env.example .env
vim .env
```

若不会用 vim：可用 `nano .env`（Ctrl+O 保存，Ctrl+X 退出）。

**把下面内容改成你的（可直接参考）：**

```bash
PUBLIC_HOST=47.103.157.213

POSTGRES_USER=vgsolar
POSTGRES_PASSWORD=VgSolar_Pg_2026_xK9m
POSTGRES_DB=vgsolar

JWT_SECRET=VgSolar_Jwt_Secret_32chars_min_abc123

EMQX_APP_USERNAME=app_user_001
EMQX_APP_PASSWORD=VgSolar_Mqtt_2026_pQ7n

API_BOOTSTRAP_EMAIL=test@vgsolar.local
API_BOOTSTRAP_PASSWORD=Test123456!
```

> 密码示例请 **自行改成更复杂的**，上面只是格式参考。  
> 生成随机密码：`openssl rand -base64 24`

---

## 第六步：拉镜像、构建、启动全部服务

```bash
cd /opt/vgsolar/deploy

# 预拉镜像（首次约 5～15 分钟，取决于网速）
docker compose pull

# 构建 API 镜像
docker compose build --no-cache

# 后台启动
docker compose up -d

# 查看状态（四个都应是 running）
docker compose ps
```

**预期 `docker compose ps` 输出：**

| 容器名 | 状态 |
|--------|------|
| vgsolar-emqx | running |
| vgsolar-postgres | running |
| vgsolar-api | running |
| vgsolar-nginx | running |

### 若某个容器不是 running

```bash
docker compose logs emqx
docker compose logs api
docker compose logs postgres
docker compose logs nginx
```

把报错内容复制下来排查。2G 内存常见问题是启动慢，等 1～2 分钟再 `docker compose ps`。

---

## 第七步：浏览器配置 EMQX（MQTT 账号）

### 7.1 打开管理页面

在你 **本地电脑浏览器** 访问：

```text
http://47.103.157.213:18083
```

默认登录：

- 用户名：`admin`
- 密码：`public`

**登录后立即修改 admin 密码。**

### 7.2 创建 MQTT 用户（App 和机器人要用）

1. 左侧 **访问控制** → **认证** → **密码认证** → **用户管理**
2. 点击 **添加**
3. 填写：
   - 用户 ID：`app_user_001`（与 `.env` 里 `EMQX_APP_USERNAME` 一致）
   - 密码：与 `.env` 里 `EMQX_APP_PASSWORD` 一致
4. 保存

---

## 第八步：验证 HTTP API

**在服务器上**执行：

```bash
# 健康检查
curl -s http://127.0.0.1:8000/health
curl -s http://47.103.157.213/api/health

# 登录（密码与 .env 中 API_BOOTSTRAP_PASSWORD 一致）
curl -s -X POST http://47.103.157.213/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.local","password":"Test123456!"}'
```

登录成功会返回 JSON，里面有 `access_token`。复制 token 后：

```bash
# 把 YOUR_TOKEN 换成上一步拿到的 access_token
curl -s http://47.103.157.213/api/devices \
  -H "Authorization: Bearer YOUR_TOKEN"
```

应返回含 `rk3588` 设备的列表。

---

## 第九步：验证 MQTT（可选但建议做）

**在服务器上**安装测试客户端：

```bash
apt-get install -y mosquitto-clients
```

**终端 1 — 订阅：**

```bash
mosquitto_sub -h 127.0.0.1 -p 1883 \
  -u app_user_001 -P '你的EMQX_APP_PASSWORD' \
  -t 'device/rk3588/telemetry' -v
```

**终端 2 — 发布模拟数据：**

```bash
mosquitto_pub -h 127.0.0.1 -p 1883 \
  -u app_user_001 -P '你的EMQX_APP_PASSWORD' \
  -t 'device/rk3588/telemetry' \
  -m '{"schema":"vgsolar.cloud_comm.v1","type":"telemetry","device_id":"rk3588","timestamp_ms":1715000000000,"status":{"battery_percent":85.0,"fault_code":0,"fcu_connected":true}}'
```

终端 1 应打印收到的 JSON。

---

## 第十步：填联调信息表（发给硬件组 / App 开发）

```text
========== 光伏机器人联调环境 ==========
ECS 公网 IP：47.103.157.213

--- MQTT ---
Broker：tcp://47.103.157.213:1883
用户名：app_user_001
密码：（私下发送，勿贴群）
设备 ID：rk3588

订阅：device/rk3588/telemetry / event / connection
发布：device/rk3588/cmd / remote / config

--- HTTP API ---
Base URL：http://47.103.157.213/api
测试账号：test@vgsolar.local
测试密码：（私下发送）

--- EMQX 管理台 ---
http://47.103.157.213:18083
========== END ==========
```

---

## 常用运维命令速查

```bash
cd /opt/vgsolar/deploy

# 查看容器状态
docker compose ps

# 查看日志
docker compose logs -f --tail=100

# 重启全部
docker compose restart

# 停止全部
docker compose down

# 修改 .env 后重新启动
docker compose down && docker compose up -d

# 查看内存（2G 机器经常看）
free -h
docker stats --no-stream
```

---

## 常见问题

| 现象 | 处理 |
|------|------|
| SSH 连不上 | 检查安全组是否放行 22；密码是否正确 |
| 浏览器打不开 :18083 或 /api | 检查安全组 18083、80；`docker compose ps` 是否 running |
| `docker pull` 超时 | 配置 `/etc/docker/daemon.json` 镜像加速 |
| 容器反复重启 | `docker compose logs api`；2G 内存不足时确认 Swap 已启用 `free -h` |
| MQTT Not authorized | EMQX 控制台是否创建了 `app_user_001` 用户 |
| API 登录 401 | 检查 `.env` 密码与 curl 里 JSON 是否一致 |

---

## 你这台服务器的特别说明

| 项目 | 说明 |
|------|------|
| **2G 内存** | 已加 Swap；若仍卡顿，建议在阿里云 **资源变配** 升到 4G |
| **3M 带宽** | 联调够用；拉 Docker 镜像时会慢，耐心等 |
| **Ubuntu 24.04** | 与教程完全兼容，无需降级 |
| **40G 磁盘** | 当前部署约占 2～5G，空间充足 |

---

**下一步**：按 **第一步 → 第十步** 顺序执行。某一步报错时，把 **完整命令 + 完整报错** 发给我，我帮你看。
