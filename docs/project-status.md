# Android App 当前状态

更新日期：2026-09-29

## 开发基线

- 当前分支：`feature/app-update-channel`；
- Map V2、V4.3 任务状态和 V6 遥测已集成在当前 feature；
- GitHub Actions 测试渠道发布成功：运行 `36548607538`；
- 已发布测试包：`versionCode=100010`、`1.3.10-test+5eab2d70`；
- 发布包 SHA-256：`5e951f04a35d0c07c6a178d7a8767fcaf99555ab4176839c591300ab40355a7c`；
- APK v2 签名有效，与本地固定发布证书一致。

## 已完成

- 通过鉴权 HTTP 同步 Map V2，完成设备、版本、checksum 和大小校验；
- `pose` 地图身份错配隐藏与重新同步；
- V6 遥测可空解析、旧值清除和离线陈旧处理；
- V4.3 根任务与内部子任务状态归属；
- `test`/`stable` App 更新渠道、固定签名、HTTPS 下载与安装前校验；
- JKS 和真实凭据均不进入 Git。

## 待完成

- Robot/Cloud/App 三方实机联调；
- Robot 地图上传、激活、重启恢复与 `pose` 切换时序证据；
- V6 真实传感器数据和无效值组合测试；
- MANUAL、20 Hz 遥控、松手/超时/ESTOP 零速证据；
- 联调通过后通过 PR 合入 `develop`，再决定 `stable` 发布。

## 回退与安全

- feature 修改均由 Git 提交保留，可按提交回退；
- 已发布 App 不覆盖，回退时以更大 `versionCode` 重新发布旧代码；
- 签名库备份位于工作区同级 `import-message/app-signing/`，不属于任何 Git 仓库。
