# 提案：控制器发布文件统一命名为 `msst-net-controller-<type>-…`

状态：**草案**。本仓库的 `release.yml` 已改用新名称，并要求 `min_installer` 3.1.4；
需要安装器（`msst-net-installer-31x`）配合修改后才能发布。

## 改动

控制器 Release 中的每个文件都以 `msst-net-controller-<type>-` 开头，`type` ∈ `webui`、
`cli`、`desktop`、`android`：

| 类型 | 旧名称（≤ 3.1.8） | 新名称 |
|---|---|---|
| WebUI | `msst-net-webui-<os>-<arch>[.exe]` | `msst-net-controller-webui-<os>-<arch>[.exe]` |
| CLI | `msst-net-cli-<os>-<arch>[.exe]` | `msst-net-controller-cli-<os>-<arch>[.exe]` |
| 桌面版 | `msst-net-controller-desktop-<os>-<arch>.<ext>` | 不变 |
| Android | `msst-net-controller-android-<abi>.apk` | 不变（不进清单） |

不变的部分：
- 可执行文件、服务和命令的名称：仍是 `msst-net-webui`、`msst-net-cli`，包括
  `versions/<版本>/` 中的文件名、`msst-net-webui.service`、`/usr/local/bin/msst-net-cli`。
  安装器本来就会把下载的文件改名后放到固定路径（`layout.rs` 的 `webui_exe` / `cli_exe`）。
- 清单中的 `component` 值（`webui`、`cli`、`desktop`）。
- Docker 镜像 `abjust/msstnet31x-webui` 及其中的 `/usr/local/bin/msst-net-webui`。
- Core 仓库的文件名（`msst-net-core-*`、`wintun-*`）：本提案只针对控制器仓库。

## 契约改动（`docs/DEPLOYMENT_CONTRACT.md`）

- §2.1 命名：WebUI 一行改为 `msst-net-controller-webui-<os>-<arch>[.exe]`，CLI 一行改为
  `msst-net-controller-cli-<os>-<arch>[.exe]`；加一句“控制器仓库的文件统一以
  `msst-net-controller-<type>-` 开头”。
- §2.2 清单示例中的 `name` 改为新名称。

## 安装器实现要点

- `manifest.rs`
  - `Component::stem()`：`Webui` → `msst-net-controller-webui`，`Cli` → `msst-net-controller-cli`。
    `asset_name()` 因此生成新名称，`release manifest` 也会按新名称识别文件。
  - `parse_asset_name()` **同时接受旧名称**（`msst-net-webui-…`、`msst-net-cli-…`），因为
    回滚和 `--controller-version` 仍会安装 3.1.8 及更早的控制器 Release，它们的清单用的是旧名称。
    `Manifest::validate()` 依赖它，所以新旧两种名称的清单都能通过校验。
  - 匹配顺序：`msst-net-controller-webui`、`msst-net-controller-cli` 与
    `msst-net-controller-desktop` 没有互为前缀的关系；旧名称与新名称也互不冲突。
  - 测试：新名称的 `asset_name` / `parse_asset_name` 往返测试；旧名称仍能解析。
- 版本：以上改动发布为安装器 **3.1.4**（本仓库的 `MIN_INSTALLER` 按此填写，版本号不同时请同步修改）。

## 兼容性

- 安装器 ≤ 3.1.3 读取新清单时，`validate()` 先检查 `min_installer`，会给出“该版本需要安装器
  3.1.4 或更新”的提示，然后停止；这一步发生在改动任何已安装内容之前。
- 安装器 3.1.4 能安装新旧两种 Release。
- 发布顺序：先发布安装器 3.1.4，再发布使用新名称的控制器版本；生成清单用的安装器
  （`MSST_INSTALLER_REF`，未设置时为默认分支）也必须是 3.1.4 或更新，否则新名称的文件
  会被当作不符合命名约定而跳过。
