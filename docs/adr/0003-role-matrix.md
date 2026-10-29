# ADR-0003 五角色权限矩阵

- 状态：已采纳
- 日期：2026-10-29
- 决策者：用户（「按市面情况决定」）

## 背景

现有角色仅 SUPER_ADMIN / ADMIN / OPERATOR 三类，无法覆盖机床厂现场分工（工艺员编制刀路、质检员只读追溯等）。规范要求最小权限。

## 决策

采用机床行业惯例的五角色，映射到现有 12 模块权限点：

| 角色 | 定位 | 模块权限 |
|---|---|---|
| SUPER_ADMIN | 系统所有者/实施方 | 全部 + 发布签名密钥管理 + 租户管理 |
| ADMIN | 厂长/车间管理员 | 全部业务模块（users/devices/app 发布等） |
| PROCESS_ENGINEER | 工艺员 | overview + tools/materials/parts/plans/drawings 读写 |
| OPERATOR | 机床操作员 | overview + device + tasks（执行/回传）+ 本机激活 |
| QUALITY | 质检员 | overview + tasks/logs 只读 |

实现要点：
1. 角色枚举存 users 表 `role` 列；权限点在服务端按模块键校验（沿用现有 `requiredPermission()`）。
2. 「只读」= 仅放行 GET；写操作（POST/PUT/DELETE）按模块写权限拦截。
3. QUALITY 与 OPERATOR 无 users/devices 写权限；PROCESS_ENGINEER 无 app 发布与 users 权限。
4. 角色变更即时生效（会话内缓存 TTL 不续期旧权限）。

## 备选方案

- A. 完全自定义权限点勾选：灵活但现场培训成本高，五角色模板已覆盖主流分工，否决（保留未来扩展）。
- B. 维持三角色：质检/工艺场景无法落地最小权限，否决。

## 后果

- 正面：现场可直接按岗位开账号；越权面收窄。
- 代价：既有账号需指定新角色（默认映射：admin→ADMIN、其余→OPERATOR）。
- 验证：每角色越权访问测试（写操作 403、越模块 403）。
