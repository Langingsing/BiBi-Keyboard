# Android Debug CI

复用既有工作流：[`release-on-version-bump.yml`](.github/workflows/release-on-version-bump.yml)，GitHub 页面中的名称仍是 **Release on Version Bump**，不增加另一份工作流。

`Langingsing/BiBi-Keyboard` fork 在每次 push 或手动 `workflow_dispatch` 时运行新增的 `debug` job，持续检查源码并提供可安装的 arm64 Debug APK；这个 job 不创建 GitHub Release、不修改版本号、不推送提交，也不部署到应用商店。

原有 `release` job 的步骤与 `force_release` 手动输入保留，但 job 限定在上游 `BryceWG/BiBi-Keyboard` 运行，push 仍只限 main/master，手动发布行为不变。扩大 workflow 的 push 触发范围后，版本检查仍防止上游无版本变化时发布。两个 job 分开是为了让 fork 的 CI 仅有只读权限，而原发布 job 的写权限不会用于 fork。

## 签名 Release APK

本 fork 的 `signed_release` job 在 main 分支 push 时自动运行；也可手动运行工作流，保持 `build_signed_release=true`。它构建经过压缩的 arm64 release APK，独立上传 Actions artifact，不创建公开 GitHub Release，不依赖版本号变化。

仓库 Secrets 配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；使用固定 release 密钥，后续版本沿用相同签名。密钥仅在构建步骤注入，临时文件退出时清理，不进入源码、构建缓存或 artifact。release job 只有 `contents: read` 权限。此签名与先前的 Android Debug 签名不同，不能直接覆盖旧 debug 安装。

产物 `BiBi-Keyboard-release-arm64-v8a-<完整提交 SHA>-<运行次数>` 保留 30 天，包含 APK、SHA256SUMS、签名验证、APK 元数据和构建来源。构建时核对固定证书 SHA-256 `8842fb6fa8fe13be2468f5a9160255b4841ed093892e7a0082ba5102e48353c7`、arm64 ABI 和非 debuggable 属性。

原有 debug job 继续执行完整 lint/测试，已知 Chinese-ITN 基线测试失败仍会让整个 workflow 显示失败；请分别核对 signed_release 的执行结果和测试报告，不能把出包成功称为全套测试通过。

## 检查和产物

1. 在 GitHub 托管的 `ubuntu-24.04` 上配置 Temurin JDK 21，并验证 Gradle wrapper。
2. 安装/确认 Android SDK 37.0（`platforms;android-37.0`）和 AGP 9.2 默认的 Build Tools 36.0.0。Gradle 9.4.1、AGP 9.2.1 由仓库配置决定。
3. 运行全部 main/test Kotlin 源码格式检查：`:app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck`。
4. 获取固定版本且经过 SHA-256 校验的 Chinese-ITN 参考语料，运行完整的 `:app:testDebugUnitTest`，不使用测试筛选或跳过规则。
5. 运行 `:app:assembleDebug -PabiFilters=arm64-v8a`；检查 APK 签名及 native ABI，生成 `SHA256SUMS`。
6. 上传 APK、校验文件、构建来源说明，以及完整 JUnit XML/HTML 和 ktlint 报告。保留 14 天。

在 fork 的新增 `debug` job 中，环境准备成功后，格式检查、完整测试、APK 构建分别执行；前一项失败不会阻止后面的诊断和出包。这个 job 没有 `continue-on-error`，任何失败仍会令工作流失败。因此，红色运行也可能有可下载 APK，不能把“有 APK”等同于“所有测试通过”。原有 release job 的历史配置单独保留。

运行摘要显示各阶段状态，并从同一次完整测试的 XML 汇总 Tencent 测试结果。不会再运行筛选测试覆盖完整报告。相同分支的新 push 会取消旧运行；单次任务最长 60 分钟。

## 下载和安装

在 fork 的 **Actions → Release on Version Bump → 对应提交的运行** 中，查看 `Lint, full unit tests, and arm64 debug APK` job 的运行摘要，或运行页底部 **Artifacts**：

- `BiBi-Keyboard-debug-arm64-v8a-<完整提交 SHA>-<运行次数>`：APK、`SHA256SUMS`、`build-info.txt`
- `Android-debug-reports-<完整提交 SHA>-<运行次数>`：完整测试和格式检查报告

GitHub 下载 artifacts 通常需要登录。下载并解压 APK 压缩包后，在该目录执行：

```sh
sha256sum --check SHA256SUMS
```

每次全新 runner 使用临时 Android debug 签名，不使用发布密钥。该 APK 可能无法覆盖安装上游正式版、之前本机构建的 Debug 版，或另一次 CI 的 Debug 版；更换安装前先备份设置。APK 不内置免费服务 Key、PocketBase 地址或腾讯云凭证，使用在线供应商时需自行配置。

## 已知基线测试失败

截至本次接入，本地完整运行 682 项测试，681 项通过、1 项失败，无跳过。失败的是原有 `ChineseItnTest.normalizesChineseItnReferenceCases`，对 328 条外部参考语料有 23 条不匹配。腾讯云接入没有改动生产 ITN 规则或这一测试；工作流保留完整测试并如实报告失败。当前失败详情以对应运行的 JUnit 报告为准，新增失败也必须独立排查。

参考语料来自 [HaujetZhao/Chinese-ITN 的固定提交](https://github.com/HaujetZhao/Chinese-ITN/blob/7c5cc3d46491bdaff612d97cd7b2b395a62c98e4/test_cases.txt)：

- 提交：`7c5cc3d46491bdaff612d97cd7b2b395a62c98e4`
- SHA-256：`1ae6dd7f6e12a41ab3f5c9b39d8a35af725489d896c67de7c9ad85d841bb29bd`

上游未发现明确的再分发许可，所以工作流仅在运行时下载到已忽略的 `references/OSS/Chinese-ITN/test_cases.txt`，不把语料纳入仓库、APK 或 artifact。下载或校验失败会明确报错，不会跳过这项测试后声称全套通过。

## 本地复现

先安装 JDK 21、Android SDK 37.0、Build Tools 36.0.0，并配置 `JAVA_HOME` 和 `ANDROID_HOME`。使用工作流中同一 URL 和 SHA-256 准备参考语料，然后分别执行以下命令并记录各自退出状态：

```sh
unset SF_FREE_API_KEY POCKETBASE_BASE_URL KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD
./gradlew --no-daemon --max-workers=2 :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest
./gradlew --no-daemon --max-workers=2 :app:assembleDebug -PabiFilters=arm64-v8a
```

## 权限和维护

- Fork 的 `debug` job 仅使用 `contents: read`；checkout 不保存凭证，debug job 不使用签名 Secrets，不增加 OIDC 或发布权限
- debug job 的 Gradle 命令显式清除内置服务、PocketBase 和发布签名环境变量；signed_release 仅注入专用签名变量
- 新增 debug job 的 GitHub 与 Gradle 官方 Actions 固定到完整提交 SHA，并在旁边标注版本
- debug job 使用 GitHub 基础缓存，signed_release 禁用构建缓存；两者都不上传依赖图或发布 Build Scan
- 不缓存临时 debug keystore，也不上传源代码、完整工作目录或参考语料
- 原有 release job 的步骤保持原样，并保留串行、不取消进行中发布的并发策略；fork CI 按分支取消被新提交替代的运行

工具链依据：[AGP 9.2 兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、[sdkmanager](https://developer.android.com/tools/sdkmanager)、[GitHub Ubuntu 24.04 镜像](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)。更新 Action 时，应先在其官方仓库确认 release 对应的完整 commit SHA，再更新工作流。

## English summary

The existing Release on Version Bump workflow is reused. On Langingsing/BiBi-Keyboard, every push and manual dispatch runs the read-only debug job: Kotlin main/test lint, the full debug unit suite, and an arm64 debug APK build. The original release steps remain intact and can run only in BryceWG/BiBi-Keyboard, retaining their original main/master/manual scope. Failures remain failures, but APK assembly/upload continues after a failed check when setup succeeded. Reports and APK/checksum artifacts last 14 days. The Chinese-ITN corpus test has a known baseline mismatch; its pinned corpus is downloaded and verified at runtime rather than redistributed. Tencent results are summarized from the full test report. The separate signed_release job uses repository signing secrets and uploads a verified release APK artifact without publishing a GitHub Release; a fresh runner's debug key may prevent updating over earlier installations.
