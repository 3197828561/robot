# HTTP API 协作与 App 联调指南

面向 **第一次开发 App** 的同学：HTTP 接口不会自动生成，需要 **先写契约 → 后端实现 → App 按契约调用**。

## 1. 本项目的接口在哪里？

| 文件 | 作用 |
|------|------|
| [openapi.yaml](./openapi.yaml) | 接口契约（路径、字段、示例） |
| [../deploy/api/app/main.py](../deploy/api/app/main.py) | 阿里云 FastAPI 实现 |
| App `network/http/ApiService.kt` | Retrofit 调用定义 |

## 2. 推荐协作流程

1. **改 openapi.yaml** → 与硬件组确认字段  
2. **改 FastAPI** → `docker compose up -d --build api`  
3. **curl / Postman 验证** → 再改 Android  
4. **App `local.properties`** 写入 `api.base.url`

## 3. Postman / curl 快速验证

```bash
# 登录（替换为你的 ECS IP）
curl -X POST http://47.103.157.213/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.local","password":"Test123456!"}'

# 设备列表（替换 TOKEN）
curl http://47.103.157.213/api/devices \
  -H "Authorization: Bearer TOKEN"
```

## 4. Android 侧如何调用

- **Retrofit** + **OkHttp** 拦截器自动附加 `Authorization: Bearer <token>`  
- Token 由 `SessionManager` 在登录成功后持久化  
- Base URL 来自 `BuildConfig.API_BASE_URL`（读取 `local.properties`）

## 5. 与 MQTT 的分工

| 能力 | 协议 |
|------|------|
| 登录、设备列表、作业记录、固件、WiFi | **HTTP** |
| 实时状态、遥控、启停急停 | **MQTT**（见 API.md / cloud_comm） |

## 6. 新增接口时怎么做

1. 在 `openapi.yaml` 增加 path  
2. 在 `main.py` 实现  
3. 在 `ApiService.kt` 增加方法  
4. 在对应 Repository / ViewModel 调用  
5. 更新 [api-gap-extension.md](./api-gap-extension.md) 若涉及说明书新字段
