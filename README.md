# MSST-Net Controller 3.1

MSST-Net 是面向朋友联机、Minecraft 等游戏和小型可信网络的私有虚拟局域网。
本仓库是它的 **控制器**：用户界面，负责展示 Core 的状态并把用户操作转成 IPC 请求，
自身不做任何网络决策。网络功能由 [Core](https://github.com/MSST-Net-Developers/msst-net-core-31x)
提供，安装、升级与回滚由 [安装器](https://github.com/MSST-Net-Developers/msst-net-installer-31x) 负责。

一套 Kotlin / Compose Multiplatform 界面，三种形态：

| 形态 | 说明 |
|---|---|
| 桌面端 | Windows / macOS / Linux，连接本机以系统服务运行的 Core |
| Android | Core 内置在应用中，以 VPN 服务运行 |
| WebUI | `msst-net-webui`，通过 HTTPS 远程管理一台设备（需设置访问密码），也提供 Docker 镜像 |

## 文档

使用教程、部署说明与常见问题见文档站：**<https://msst-net.msst2031.cn>**

本仓库 `docs/` 下是面向开发者的文档（结构与构建、各形态差异、Docker 部署等）。

## 安装

推荐使用安装器，它会同时安装 Core 与所选控制器。各平台的安装包、Android APK、
WebUI 可执行文件见本仓库的 Releases；WebUI 的 Docker 镜像为 `abjust/msstnet31x-webui`。

## 从源码构建

需要把 Core 仓库检出到本仓库旁边（`../msst-net-core-31x`）。

```sh
cargo test --features webui                        # 控制器库与 WebUI 服务端
cd compose && ./gradlew :shared:jvmTest            # 界面逻辑
```

桌面端、Android 与 WebUI 的构建步骤见 [docs/CONTROLLER_3.1.md](docs/CONTROLLER_3.1.md)。

## 许可证

[MIT](LICENSE)。第三方依赖的许可证见
[THIRD_PARTY_LICENSES.txt](compose/shared/src/commonMain/composeResources/files/THIRD_PARTY_LICENSES.txt)，
在应用的“设置 → 关于 → 开源许可”中也可查看。
