# 腾讯云实时 ASR / Tencent Cloud realtime ASR

## 配置

1. 在自己的腾讯云账户开通语音识别服务，并确认所选实时识别模型的计费方案。
2. 在「语音识别」设置中选择「腾讯云」。填写自己的 AppID、SecretId 和 SecretKey。
3. 引擎默认 `16k_zh`。可按腾讯云文档填写其他 `16k_` 开头的模型，例如 `16k_en`、`16k_zh-TW` 或 `16k_zh_en_2.0`。模型是否可用取决于账户权限和服务端支持。
4. 使用输入法、悬浮球或录音测试进行实时识别。外部 SpeechRecognizer / AIDL 和主备识别复用相同适配器。

本适配器只支持实时识别。历史音频重识别使用腾讯云作为主引擎时会显示不支持文件回退的提示；使用其他主引擎重识别时，不会启用腾讯云作为备用引擎。实时录音的主备识别不受影响。

## 凭证与隐私

- 仓库和 APK 不包含腾讯云凭证；用户必须自行填写。
- 与其他供应商一样，配置保存在应用私有设置中。设置备份会包含凭证，请妥善保管且不要公开分享。
- SecretKey 仅用于本地 HMAC 签名，不会作为查询参数发送。连接使用腾讯云官方 WSS 端点；SecretId 和短期签名是协议要求的一部分。
- 适配器不会记录凭证、签名 URL 或识别文本到 API 日志。
- 开始识别后，音频会发送至腾讯云并可能产生费用。请自行在腾讯云控制台使用最小权限、管理密钥并设置费用提醒。

## 协议与范围

- 官方端点：`wss://asr.cloud.tencent.com/asr/v2/<AppID>`
- 按参数名排序的未编码查询串参与 HMAC-SHA1，再对 Base64 签名和查询值进行 URL 编码
- 等待 `code=0` 服务握手成功后才上传音频
- 16 kHz、16-bit、单声道 PCM，按实时速率发送
- 兼容传统 `result` 和大模型 2.0 的 `sentences.sentence_list` 结果，依据 `index` / `sentence_id` 更新句子，避免重复拼接；输入法仅输出文本，不显示说话人标签
- 停止时先排空音频队列，再发送 `{"type":"end"}`，等待 `final=1`
- 有界缓冲、握手与结束超时、错误处理和会话隔离，防止旧连接回调污染新会话

参考：[腾讯云实时语音识别 WebSocket 文档](https://cloud.tencent.com/document/product/1093/48982) · [实时语音识别 V2](https://cloud.tencent.com/document/product/1093/131127)

## 构建与测试

项目需要 JDK 21 和 Android SDK 37；使用仓库 Gradle wrapper。默认构建 `arm64-v8a`：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 路径为 `app/build/outputs/apk/debug/app-debug.apk`。使用 Android debug 签名，与上游发布版签名不同，不能直接覆盖上游安装；更换安装前请先备份设置。无内置免费服务密钥的源码构建需要用户自行配置对应供应商。

自动化测试覆盖请求签名/编码、结果聚合、音频队列与终止状态、配置和路由。它们使用虚构凭证及本地模拟消息，不会发送真实用户音频或消耗腾讯云服务。真实设备麦克风和真实腾讯云账户验证需用户另行完成。

## English summary

Select Tencent Cloud in ASR settings, enter your own AppID, SecretId and SecretKey, and choose a supported `16k_` model (default `16k_zh`). The adapter uses Tencent's official signed WebSocket protocol with paced mono PCM, incremental results, graceful finalization and bounded buffering. It supports direct microphone and externally pushed PCM, including primary/backup paths. External audio producers should feed PCM in real time; the bounded queue fails explicitly on excessive buffering. Both legacy and V2 sentence results are supported. Recorded-history batch recognition is intentionally unsupported, and Tencent is omitted as a backup for historical replay. No credentials are embedded; exported settings include credentials. Real recognition sends audio to Tencent and may incur charges. Debug APKs use a different signing identity from upstream releases.
