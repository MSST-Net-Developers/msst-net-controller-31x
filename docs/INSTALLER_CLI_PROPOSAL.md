# 提案：在部署契约中加入命令行控制器 `msst-net-cli`

状态：**已落实**——安装器 3.1.4（`msst-net-installer-31x` 的 `docs/DEPLOYMENT_CONTRACT.md`、
`manifest.rs`、`layout.rs`、`native.rs`）。控制器从 3.1.8 起在 Release 中提供 `msst-net-cli-*`，
清单列出 `cli` 组件并要求 `min_installer` 3.1.4；更早的安装器无法读取该清单，会在改动任何
已安装内容之前失败。

## 背景

契约 §1 写道“3.0.x 的 CLI 控制器在 3.1 中没有对应的交互式程序，因此不再提供”。
控制器下一个版本起提供 `msst-net-cli`：在运行 Core 的设备上，以终端方式提供与 WebUI 相同的
功能（`docs/CONTROLLER_3.1.md` 的 CLI 一节）。它是普通用户运行的一次性命令，不是服务；
权限与桌面控制器相同，由 Core 按对端身份判定（`msst-net` 组成员为 admin，其他用户只读）。

## 契约改动

### §1 组件关系

- “控制器三选一：桌面版、WebUI、不安装（仅 Core）”改为**四选一**：桌面版、WebUI、
  命令行（`msst-net-cli`）、不安装（仅 Core）。选“仅 Core”时不安装任何控制器，CLI 也不装。
- 删除“3.0.x 的 CLI 控制器……不再提供”一句，改为：
  > 命令行控制器 `msst-net-cli` 由普通用户在终端中运行，经 IPC 连接 Core，不是服务；
  > 适合没有图形界面、又不需要远程管理的设备。
- 组件图中增加 `msst-net-cli（普通用户运行，IPC）`。

### §2.1 命名

| 组件 | 文件 | 来源 |
|---|---|---|
| 命令行控制器（controller 仓库） | `msst-net-cli-<os>-<arch>[.exe]` | controller 仓库 `cargo build --release --bin msst-net-cli` |

六个平台与 WebUI 相同（linux/windows/macos × x86_64/aarch64；Linux 为 musl 静态链接）。

### §2.2 清单

- `component` 增加 `"cli"`，`format` 只能是 `binary`。
- 控制器清单可列 `webui`、`desktop`、`cli`；Core 清单仍不能列 `cli`。
- 示例：
  ```json
  { "name": "msst-net-cli-linux-x86_64", "component": "cli", "os": "linux",
    "arch": "x86_64", "format": "binary", "size": 4194304, "sha256": "…" }
  ```
- **兼容性（已核实）**：现有安装器的 `Component` 是严格枚举（`manifest.rs`），遇到
  `"cli"` 会在解析阶段拒绝整份清单，`min_installer` 检查也执行不到。按 §6，这一步发生在
  改动任何已安装内容之前，所以失败是安全的，但旧安装器会因此无法安装新的控制器 Release。
  建议的顺序：
  1. 安装器先发布支持 `cli` 的版本，同时给 `Component` 加 `#[serde(other)] Unknown`
     并在安装时跳过它，以后再增加组件就不会让旧安装器失败。
  2. 之后的控制器 Release 才让清单列出 `cli`，并把 `min_installer` 设为第 1 步的版本。
     在此之前，`release manifest` 会跳过 `msst-net-cli-*`，本仓库无需改动。

### §3 安装布局

| | Linux | macOS | Windows |
|---|---|---|---|
| 可执行文件 | `<根>/versions/<版本>/msst-net-cli` | 同左 | `<根>\versions\<版本>\msst-net-cli.exe` |
| 命令入口 | 符号链接 `/usr/local/bin/msst-net-cli` → 当前版本 | 同左 | `<根>\bin\msst-net-cli.exe`（切换版本时复制），`<根>\bin` 加入系统 `PATH` |

- 升级与回滚时，命令入口和服务定义一起切换到新版本（§6 第 5 步）。
- 只用于原生安装：Docker 部署仍只包含 WebUI（`--docker` 需要 `--controller webui`，不变）。
- 控制器之间互相切换时（例如从 CLI 改为 WebUI，或反过来），按现有逻辑移除原控制器，
  包括它的命令入口。
- 不改变 Core 的 socket 权限：非 `msst-net` 组成员运行时只读，这与桌面控制器一致。

### §6 事务

- 第 2 步自检：选择 CLI 时，除 `msst-net-core --version` 外再运行 `msst-net-cli --version`
  （输出包含控制器版本号）。
- 第 5 步：CLI 不是服务，健康检查只检查 Core；Core 通过后再切换命令入口，失败回滚时
  把入口一起恢复。

### §7 卸载

删除命令入口（Linux/macOS 的符号链接；Windows 的 `<根>\bin` 和它在 `PATH` 中的条目）。

### §8 无人值守

`--controller` 的取值增加 `cli`：

```sh
msst-net-installer install --controller cli --admin-user alice --yes --non-interactive
```

交互模式下选择控制器的问题中增加“命令行（msst-net-cli）”一项。

## 安装器实现要点

- `manifest.rs`：`Component::Cli`（`"cli"`，资产名前缀 `msst-net-cli`，格式 `Binary`），
  `Product::Controller` 允许它；补上 `asset_name` 与解析测试。
- `layout.rs`：`cli_exe(version)`，以及各平台的命令入口路径。
- `native.rs`：安装、升级、回滚、卸载时维护命令入口；Windows 把 `<根>\bin` 加入系统
  `PATH`，卸载或改选其他控制器时移除。
- 控制器选择（`--controller` 与交互菜单、`installer-state.json` 中记录的选择）增加 `cli`；
  `upgrade` / `repair` / `rollback` 沿用记录的选择。
- 选择“仅 Core”时不下载控制器 Release（与现在相同）。

## 已确定

1. CLI 与桌面版、WebUI 并列，是 `--controller` 的一个选项；选“仅 Core”时不安装任何控制器，
   包括 CLI。
2. Windows 上把 `<根>\bin` 加入系统 `PATH`。
