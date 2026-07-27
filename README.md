# OpenMinis Ubuntu Agent

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的 Android 二开版。

## 与上游 / Code 版的差异

| 项 | OpenMinis | openminis-code | **本仓库** |
|----|-----------|----------------|------------|
| Guest OS | Alpine (musl) | Alpine (musl) | **Ubuntu 24.04 (glibc)** |
| 包管理 | `apk` | `apk` | **`apt-get`** |
| Agent 核心 | Minis 自研 loop | + **Pi Agent (RPC)** | **Pi Agent + Minis loop** |
| applicationId | `com.openminis.app` | `com.openminis.code` | `com.openminis.ubuntu` |
| rootfs asset | `alpine-minirootfs.tar.gz` (~4MB) | 同左 | `ubuntu-base-rootfs.tar.gz` (~25MB slim) |

其余能力与 Minis 对齐：PRoot 沙箱、`/var/minis/*` 目录、native offload
（browser / speech / model-use / config）、Skills、Memory、Shizuku / A11y CLI、
Compose UI、Providers 等。

## 为什么用 Ubuntu

- **glibc**：很多预编译二进制（含部分 Pi / Node 生态、闭源 CLI）在 musl 上跑不动
- **apt 生态**：`python3-*`、`build-essential`、常见开发工具比 Alpine 社区包全
- 仍走 **PRoot**，不需要 root / 内核模块，和 Minis 同一套 Android 集成

## 构建

```bash
# 准备 rootfs + proot 到 assets/
./scripts/prepare_android_sandbox.sh

cd src/android
./gradlew assembleDebug
```

CI：`.github/workflows/android.yml` 会在 push 时自动准备 assets 并上传
`openminis-ubuntu-debug` artifact。

## Pi Agent

Settings → **Pi Agent (Dev Mode)**：

1. 首次进入会跑 `/usr/local/bin/pi-install`（`apt` 装 Node 22 + npm 装 pi）
2. 之后以 `pi --mode rpc` 长驻，Android 侧 `PiAgentService` 走 JSON-RPC

脚本：`scripts/install_pi.sh`（同步到 `assets/default_mount/usr/local/bin/pi-install`）。

## 许可证

继承 OpenMinis 上游许可证；Pi coding-agent 为 MIT（earendil-works/pi）。
