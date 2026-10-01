# 2026-10-01 App 用户文案与字段映射

## 输入

- 用户要求：App 至少可正常给现场使用；在 `robot/docs/` 给出每个按钮的用途、绑定的 HTTP/MQTT 字段、取值和界面展示对照。
- 协议范围不变：V1–V6，仅 `crawler/crawler_00000001`。

## 实现范围

- 更多页用户可见标签改为「主控温度」「板面倾角」，不再出现 RK3588 芯片名；
- 任务状态中文去掉「根任务」「栈深」；
- 新增 [app-control-field-mapping.md](../app-control-field-mapping.md)。

## 未改变

- 不新增 MQTT topic 或 HTTP 路径；
- 完整诊断页仍保留协议字段名，供联调使用；
- Wi-Fi 与 Robot OTA 入口仍禁用。
