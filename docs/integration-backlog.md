# 联调后迭代 Backlog

联调 v1 完成后，按优先级排入下一迭代。

## P0 — 阻塞上线

| 项 | 说明 | 依赖 |
|----|------|------|
| | | |

## P1 — 说明书完整对齐

| 项 | 说明 | 当前状态 |
|----|------|----------|
| 场景参数卡片 | 组件类型/栅线/尺寸/片数/遍数 MQTT 或 HTTP 绑定 | XML 占位 |
| 控制模式卡片 | 停泊模式/清洁模式/行数限制可编辑下发 | XML 占位 |
| 综合信息 | 清洁方向、机器方向 | 未绑定 |
| 说明书入口 | `btnManual` 打开 PDF/Web | Toast 占位 |

## P2 — HTTP 业务完善

| 项 | 说明 |
|----|------|
| 作业记录 | JobListActivity 与后端真实数据 |
| 固件升级 | FirmwareActivity OTA 流程 |
| WiFi 设置 | WifiActivity 配网下发 |
| 多设备切换 | 主页快速切换 device_id |

## P3 — 安全与运维

| 项 | 说明 |
|----|------|
| MQTT TLS 8883 | 替换明文 1883 |
| 密码轮换 | 联调密码全部更换 |
| EMQX IP 白名单 | 缩小 1883 暴露面 |
| HTTPS API | Nginx 证书 |

## 技术债

| 项 | 说明 |
|----|------|
| `api-gap-extension.md` | 说明书字段与 cloud_comm 缺口对照 |
| 单元测试 | RoverStatus JSON 解析、debounce |
| ProGuard | release 混淆规则 |

## 参考

- [integration-protocol-v1.md](./integration-protocol-v1.md)
- [api-gap-extension.md](./api-gap-extension.md)
