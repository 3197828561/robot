# App 文档索引

`robot` 仓库的文档范围限定为 App 需求和 App 实现记录。云服务部署文档归 `cloud-server`，Robot 硬件交接和跨仓库联调记录归 `robot-integration`。

## `requirements/`

保存 V1–V6 需求原文，不直接改写历史版本。冲突时按最新专项文档解释：

- V3：未被后续文档覆盖的基础 App 契约；
- V4.3：当前任务命令、根任务和内部子任务契约；
- V5：当前 Map V2 契约，覆盖旧 MQTT `map`/匿名 URL 方案；
- V6：当前设备遥测契约。

`map_planner` 参考源码和大型地图样例已迁移到 `robot-integration/robot-handoff/map-planner-reference`，不再打包进 APK。App 单元测试所需的最小地图样例位于 `app/src/test/resources/maps/`。

## `changes/`

每次按新需求修改 App 时记录：输入文档、基线提交、实现范围、测试结果、未联调项和回退点。

## 当前状态

见 [project-status.md](project-status.md)。需求版本解释、Robot 交付项和三仓库联合验收记录以 `robot-integration` 为准。

## App 正式化

- [正式化产品需求](requirements/app-formalization-product-requirements.md)：页面、交互、安全、手机和平板验收口径；
- [App 正式交付清单](app-delivery-checklist.md)：App、Cloud、Robot、测试人员每次需要提供的交付物。
- [App 操作说明](app-readme/README.md)：从登录到每个按钮的前提、接口、字段值、展示和操作后反应。

正式化文档不新增接口版本。所有通信仍以 V1–V6 当前有效定义为准。
