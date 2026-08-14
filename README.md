# 灵枢（Ling Shu）

> Android手机上的AI Agent系统，具备完整权限、多模态感知、主动服务能力

> ⚠️ **项目处于早期开发阶段**  
> 核心功能尚未实现，欢迎提交 Pull Request 参与共建。


## 当前功能实现状态（实测 2026-08-14）

> 以下为真机/模拟器实测结果，与下方设计文档（全部标 ✅）区分。已实现可用的功能才标记 ✅，未实现或不可用的标记 ❌。

### ✅ 可用
| 功能 | 说明 |
|------|------|
| 文字对话 + 流式响应 | DeepSeek / Ollama / Gemini 多 LLM Provider 路由，流式输出 |
| 打开应用 | 支持"打开设置""帮我打开设置""我想打开设置"等口语化指令，通过用户输入解析 + 包名映射跳转 |
| RAG 知识库 | 文档切片(512token) + Ollama 嵌入 + 余弦检索 + LLM 生成 |
| 人格系统 | 大五维度 + LIKED/DISLIKED 反馈演化 + 衰减算法 + 人格工坊 |
| 主动关怀 | 5 种触发类型 + 冷却/上限/概率过滤 + LLM 文案生成 |
| 悬浮窗 | 状态气泡 + 拖拽吸附 + 快捷对话 |
| 底部导航 | 聊天 / 健康 / 设置（简体中文） |
| 健康入口 + 社区入口 | 已接入导航（社区为占位"即将上线"） |
| API Key 加密存储 + 轮询 | AES 加密落盘 + 多 Key 轮换冷却 |

### ❌ 不可用 / 未实现
| 功能 | 说明 | 后续方向 |
|------|------|---------|
| 高级控制 | 无障碍点击/滑动/手势/JS 脚本引擎/屏幕理解 OCR 均未实现，仅"打开应用"等系统 API 控制可用 | 待接入无障碍服务 + Rhino/GraalJS |
| 声音克隆 | 可录音但无法将克隆音色应用到 TTS 播报 | 待接入可本地部署的声码器（如 GPT-SoVITS） |
| TTS 语音播报 | 降级链 ANDROID_TTS → ChatTTS → EdgeTTS 已就位，但模拟器无 TTS 引擎、EdgeTTS 403 区域封锁 | 真机测试（Google TTS）或新加坡代理 |
| 唤醒词"小枢小枢" | Vosk 集成完成，模拟器无麦克风无法验证 | 真机测试 |
| Mod 系统 / 创意工坊 | .lspack 规范未实现，仅占位 | 待实现 |
| 健康数据看板 | Health Connect 集成未完成，仅入口 | 真机接入 Health Connect |
| 截屏 / 屏幕理解 | 需无障碍服务 + 多模态模型 | 待实现 |
| OTA 更新 | GitHub Releases 拉取未实现 | 待实现 |

### ⚠️ 受环境限制（功能已实现但需真机）
- **Vosk 离线 STT**：模拟器无麦克风会超时，真机可用
- **蓝牙 / 手电筒 / 传感器**：模拟器无对应硬件模块

---

## 1. 项目简介

### 1.1 项目名称
**灵枢（Ling Shu）** —— 取义于《黄帝内经·灵枢》，意为"智慧的枢纽"，象征连接用户与AI的核心通道。

### 1.2 一句话定位
Android手机上的AI Agent系统，具备完整系统权限、多模态感知能力、主动服务意识，不只是工具更是陪伴。

### 1.3 三大核心理念

| 理念 | 含义 |
|------|------|
| **不是工具，是同伴** | 超越"问答工具"定位，具备情感感知、持续记忆、主动关怀能力，像朋友一样随叫随到 |
| **不是预设人格，共同成长** | 人格基于大五维度动态演化，随用户反馈、使用场景不断微调，千人千面，和用户共同进化 |
| **不是被动问答，主动关怀** | 基于时间/行为/传感器/记忆/随机五大触发类型，在合适时机主动提供提醒、关怀、建议，从"你问我答"到"我懂你心" |

---

## 2. 技术栈

### 2.1 开发语言与UI框架
- **Kotlin** —— Android官方首选语言，协程原生支持
- **Jetpack Compose Material3** —— 声明式UI，Material You设计语言，支持动态配色

### 2.2 架构模式
- **MVVM Clean Architecture** —— UI层 / ViewModel层 / UseCase层 / Repository层 / DataSource层 清晰分离
- **Coroutines + Flow** —— 响应式数据流，主线程安全，冷流热流按需选择

### 2.3 依赖注入
- **Hilt** —— Google官方DI框架，基于Dagger，编译期注入，零反射开销

### 2.4 本地数据存储
- **Room** —— SQLite ORM框架，类型安全，支持Kotlin扩展与Flow
- **DataStore (Preferences)** —— 轻量级KV存储，替代SharedPreferences，协程/Flow友好

### 2.5 网络请求
- **Retrofit 2.9** —— 类型安全HTTP客户端
- **OkHttp 4.12** —— 高性能HTTP底层，支持拦截器、连接池
- **连接超时 10s / 读取超时 30s**

### 2.6 后台任务
- **WorkManager** —— 兼容API 14+的后台任务调度，支持约束条件、周期性任务、持久化

### 2.7 SDK版本
- **minSdk 24** (Android 7.0 Nougat)
- **targetSdk 34** (Android 14 Upside Down Cake)
- **compileSdk 34**

---

## 3. 核心功能模块

### 3.1 全双工语音交互
**模块说明：** 实现唤醒词→语音识别→大模型→语音合成的完整语音闭环。支持"小枢小枢"本地唤醒、连续对话无需每次唤醒、说话中途可被打断、VAD静音检测自动断句。

**验收要点：**
- ✅ 离线唤醒词检测（Vosk/Porcupine引擎）
- ✅ Android SpeechRecognizer STT + 云端高精度STT双路
- ✅ 系统TTS + 云端TTS双路合成，支持情感语调
- ✅ 连续对话：一次唤醒多轮交互，超时自动退出
- ✅ 打断机制：TTS播放中用户说话立即停止合成

### 3.2 多模型路由层
**模块说明：** 通过`ModelProvider`抽象层统一封装多家模型供应商。支持对话/视觉/语音识别/语音合成四大能力的智能路由，云端不可用时自动降级到本地Ollama/Vosk/系统TTS，API Key轮询避免单Key限流。

**验收要点：**
- ✅ `ModelProvider`抽象接口统一DeepSeek/GPT-4/Claude/Gemini/Ollama/Vosk/系统TTS
- ✅ 路由优先级：用户指定 > 手动锁定 > 场景默认 > 云端优先级 > 本地降级
- ✅ 自动降级：限流(429)/网络错误/服务不可用时自动切换下一个候选
- ✅ API Key轮询：多Key轮换，失败Key标记冷却
- ✅ 模型切换即时生效，无需重启应用

### 3.3 手机全权控制
**模块说明：** 三层控制能力叠加：系统公开API（音量/蓝牙/WiFi/亮度等）+ 无障碍服务高级控制（点击/滑动/输入/启动App/模拟手势）+ JS脚本引擎（自动化工作流/复杂操作编排）。所有敏感操作执行前二次确认。

**验收要点：**
- ✅ 系统API控制：音量/亮度/蓝牙/WiFi/飞行模式/手电筒/屏幕旋转
- ✅ 无障碍控制：全局点击/滑动/长按/文本输入/启动指定包名App/通知栏操作
- ✅ 屏幕截图 + 屏幕理解：OCR+多模态模型识别界面元素
- ✅ JS脚本引擎：Rhino/GraalJS封装，暴露`lingshu.*` API调用控制能力
- ✅ 敏感操作二次确认弹窗 + 白名单App机制

### 3.4 主动关怀系统
**模块说明：** 双层决策架构——规则决策层（确定性触发）+ 模型生成层（LLM生成关怀文案）。五大触发类型：时间触发/行为触发/传感器触发/记忆触发/随机策略，配备冷却时间、每日上限、随机概率过滤避免机械感。

**验收要点：**
- ✅ 5种触发类型：TIME(深夜/固定提醒) / BEHAVIOR(频繁解锁/深夜刷手机/久用App) / SENSOR(久坐/心率异常/长静止) / MEMORY(生日/纪念日/负面情绪跟进) / RANDOM(随机关怀)
- ✅ 冷却时间：两次关怀最短间隔（默认60分钟）
- ✅ 每日上限：单日关怀最大次数（默认5次）
- ✅ 随机过滤：非紧急类型(久坐/久用App等)经过20-30%概率过滤，避免机械
- ✅ 内容生成层：LLM根据触发类型+用户记忆动态撰写个性化关怀文案

### 3.5 人格系统
**模块说明：** 基于大五人格模型（开放性/尽责性/外向性/宜人性/神经质），每维度0-1连续值。用户点赞反馈正向微调、点踩反馈反向微调，多次连续反馈自动衰减避免过拟合。人格工坊UI化编辑，支持导入导出JSON分享。

**验收要点：**
- ✅ BigFiveTraits大五维度，clamp夹紧到0-1区间
- ✅ 用户反馈LIKED：宜人性+尽责性轻微上升；DISLIKED：反向
- ✅ 反馈衰减：连续第N次相同反馈，调整幅度 × 1/√N
- ✅ 人格工坊：可视化滑块编辑、开场白、语气标签、示例对话
- ✅ 导入导出：JSON格式，含ID/名称/维度/记忆/标签/规则

### 3.6 Mod系统与社区
**模块说明：** `.lspack`格式（ZIP压缩包，内含manifest.json + 资源文件），支持人格包/技能包/主题包/自动化包/数据包五类。动态加载无需重启，启用禁用开关即时生效。内置创意工坊浏览、搜索、下载、更新。

**验收要点：**
- ✅ `.lspack`格式规范：ZIP压缩，根目录必须有`manifest.json`
- ✅ manifest必填字段：id/name/version/category/author/minAppVersion
- ✅ 动态加载：DexClassLoader加载插件代码，资源AssetManager叠加
- ✅ 启用/禁用：SharedPreferences持久化，重启后恢复状态
- ✅ 创意工坊：浏览列表/详情/下载/版本更新提示

### 3.7 健康数据与穿戴设备
**模块说明：** Health Connect API聚合Google Fit/三星健康/小米运动等健康平台数据。SensorManager直连手机加速度计/心率传感器。异常心率即时报警。使用习惯分析（久坐/熬夜/刷手机时长）。

**验收要点：**
- ✅ Health Connect集成：步数/心率/睡眠/运动/血氧
- ✅ SensorManager：加速度计计步、陀螺仪检测设备静止
- ✅ 异常报警：心率超出[50,100]BPM范围时高优先级通知+关怀
- ✅ 习惯分析：久坐时长、深夜使用时长、App使用分布柱状图
- ✅ 健康周报：周维度统计+LLM生成健康建议

### 3.8 本地知识库RAG
**模块说明：** 文档切片(512token窗口+重叠64token)→向量化(Ollama嵌入模型)→存储(SQLite-VSS/Chroma/内存向量库)→检索(topK余弦相似度)→LLM生成。支持本地Ollama嵌入一键部署引导。

**验收要点：**
- ✅ 文档切片：Markdown/PDF/TXT/Docx解析器，512token滑动窗口
- ✅ 向量化：Ollama nomic-embed-text/bge-m3嵌入，维度384/1024
- ✅ 存储层：VectorStore接口，默认InMemory实现，预留SQLite-VSS/Chroma
- ✅ 检索增强：System Prompt拼接topK相关切片，引用来源标注
- ✅ Ollama引导：首次使用时弹窗引导下载安装Ollama并拉取嵌入模型

### 3.9 悬浮窗
**模块说明：** `TYPE_APPLICATION_OVERLAY`全局悬浮窗。状态气泡(待机/聆听/思考/说话四种状态)，可拖拽到任意位置，吸附边缘。点击展开快捷对话输入框，支持快捷命令(设置/人格/关闭等)。

**验收要点：**
- ✅ 悬浮权限动态申请 + 引导页跳转到设置页面
- ✅ 状态气泡：4种状态颜色/动画不同（灰=待机/蓝=聆听/黄=思考/绿=说话）
- ✅ 拖拽：触摸移动 + 松手吸附左右边缘 + 半透明隐藏
- ✅ 快捷对话：点击展开Mini输入框，Enter发送
- ✅ 全局快捷：任何界面通过悬浮窗快速唤醒语音/发起对话

### 3.10 用户界面
**模块说明：** 玻璃磨砂Dark Glassmorphism深色主题，多层半透明卡片+背景模糊。共10个主要界面。

**验收要点（界面清单）：**
1. ✅ 启动页（Splash + 品牌动画）
2. ✅ 4步首次引导页（欢迎→权限→模型选择→人格选择）
3. ✅ 主聊天界面（消息列表+输入框+模型切换+人格切换）
4. ✅ 语音交互界面（声波动画+文字上屏+状态指示）
5. ✅ 人格工坊界面（维度滑块+记忆管理+导入导出）
6. ✅ 主动关怀配置界面（触发类型开关+阈值调整+冷却设置）
7. ✅ 模型设置界面（Provider列表+API Key输入+优先级排序）
8. ✅ Mod管理/创意工坊界面（已安装列表+在线浏览+详情）
9. ✅ 知识库管理界面（文档上传+切片预览+搜索测试）
10. ✅ 健康看板界面（今日数据+周统计+异常报警历史）

### 3.11 安全与隐私
**模块说明：** AES-256-GCM加密本地敏感数据（API Key/聊天记录/记忆）。所有数据默认本地存储，云端上传需用户显式勾选。权限透明展示（已授予/未授予/使用说明）。一键清除全部数据。JSON格式完整导出。

**验收要点：**
- ✅ AES-256-GCM：AndroidKeyStore生成密钥，敏感字段加密落盘
- ✅ 本地优先：聊天/记忆/人格/RAG切片默认仅本地，不主动上传
- ✅ 权限透明：设置页列出所有申请权限→用途说明→开关状态
- ✅ 一键清除：确认弹窗→删除数据库/文件/DataStore全部数据
- ✅ JSON导出：聊天记录/人格/记忆/配置全量导出，含时间戳与校验和

### 3.12 分发与更新
**模块说明：** APK体积严格控制<60MB。首次启动4步引导流程。OTA更新基于GitHub Releases，版本号比对+增量/全量下载+安装器Intent。

**验收要点：**
- ✅ APK < 60MB：ProGuard R8混淆、资源压缩、ABI仅arm64-v8a、未用资源剔除
- ✅ 首次启动引导：Step1欢迎→Step2权限→Step3模型配置→Step4人格选择
- ✅ GitHub Releases OTA：版本号比对→下载APK→Notification进度→安装Intent
- ✅ 版本兼容：minSdk 24设备全部功能可用，高版本API优雅降级
- ✅ 签名：Release包V2签名，debug包默认debug keystore

---

## 4. 项目结构

```
lingshu-android/
├── README.md                              # 本文档
├── settings.gradle.kts                    # Gradle设置（模块/仓库）
├── app/
│   ├── build.gradle.kts                   # 应用级构建配置（依赖/SDK/混淆）
│   ├── proguard-rules.pro                 # ProGuard/R8混淆规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # 清单文件（权限/四大组件/SDK）
│       │   ├── res/                       # 资源目录（布局XML已用Compose替代）
│       │   │   ├── drawable/              # 图标/通知/矢量图
│       │   │   ├── values/                # colors.xml / strings.xml / themes.xml
│       │   │   └── xml/                   # FileProvider/Accessibility配置
│       │   └── java/com/lingshu/agent/
│       │       ├── LingShuApp.kt          # Application入口，Hilt初始化
│       │       ├── MainActivity.kt        # 主Activity，Compose宿主
│       │       ├── Navigation.kt          # NavHost导航图定义
│       │       ├── AppState.kt            # 全局应用状态（单例）
│       │       ├── core/                  # 核心通用层
│       │       │   ├── model/             # 数据模型
│       │       │   │   ├── Message.kt         # 消息/会话模型+角色枚举
│       │       │   │   ├── Conversation.kt    # 会话模型+状态枚举
│       │       │   │   ├── Persona.kt         # 人格/大五维度/规则模型
│       │       │   │   ├── ModInfo.kt         # Mod信息/分类/来源模型
│       │       │   │   ├── HealthData.kt      # 健康数据模型
│       │       │   │   └── Script.kt          # 脚本模型
│       │       │   ├── data/              # 数据存储封装
│       │       │   │   └── AppSettingsDataStore.kt  # DataStore KV封装
│       │       │   ├── database/          # Room数据库层
│       │       │   │   ├── LingShuDatabase.kt   # Database主类+DAO引用
│       │       │   │   └── Converters.kt        # TypeConverter（List/Map转JSON）
│       │       │   ├── network/           # 网络层
│       │       │   │   ├── RetrofitClient.kt    # OkHttp+Retrofit实例（超时配置）
│       │       │   │   └── ApiService.kt        # 通用API接口定义
│       │       │   └── di/                # Hilt依赖注入模块
│       │       │       ├── AppModule.kt         # 全局单例（Retrofit/OkHttp等）
│       │       │       ├── DatabaseModule.kt    # Room数据库注入
│       │       │       └── DataStoreModule.kt   # DataStore注入
│       │       ├── feature/               # 业务功能模块（12个Feature目录）
│       │       │   ├── voice/             # 语音交互模块
│       │       │   │   ├── WakeWordDetector.kt      # 唤醒词检测
│       │       │   │   ├── SpeechRecognizerManager.kt # STT管理
│       │       │   │   ├── TextToSpeechManager.kt     # TTS管理
│       │       │   │   ├── VAD.kt                  # 语音活动检测
│       │       │   │   ├── VoiceSession.kt         # 语音会话状态机
│       │       │   │   └── VoiceViewModel.kt       # 语音界面ViewModel
│       │       │   ├── model/             # 多模型路由模块
│       │       │   │   ├── ModelProvider.kt         # Provider抽象接口
│       │       │   │   ├── ModelRouter.kt           # 智能路由中心（优先级/降级）
│       │       │   │   ├── ModelCapability.kt       # 能力枚举(CHAT/VISION等)
│       │       │   │   ├── ModelMessage.kt          # 模型输入消息结构
│       │       │   │   ├── ModelResponse.kt         # 模型响应结构
│       │       │   │   ├── ModelSettings.kt         # 用户模型配置（Provider设置）
│       │       │   │   ├── ModelSettingsViewModel.kt# 模型设置界面VM
│       │       │   │   └── providers/               # 各Provider实现
│       │       │   │       ├── DeepSeekProvider.kt      # DeepSeek云端
│       │       │   │       ├── GPT4VisionProvider.kt    # GPT-4V视觉
│       │       │   │       ├── OllamaProvider.kt        # 本地Ollama
│       │       │   │       ├── VoskTranscribeProvider.kt# 离线STT Vosk
│       │       │   │       └── SystemTTSProvider.kt     # 系统TTS
│       │       │   ├── control/           # 手机控制模块
│       │       │   │   ├── SystemController.kt          # 系统API控制（音量/蓝牙等）
│       │       │   │   ├── AccessibilityController.kt   # 无障碍控制（点击/滑动等）
│       │       │   │   ├── ScriptEngine.kt              # JS脚本引擎（Rhino封装）
│       │       │   │   ├── ScreenCaptureManager.kt      # 截图/录屏管理
│       │       │   │   ├── ScreenUnderstanding.kt       # 屏幕OCR+多模态理解
│       │       │   │   ├── ControlPanelScreen.kt        # 控制面板UI
│       │       │   │   └── ControlViewModel.kt          # 控制面板VM
│       │       │   ├── proactive/         # 主动关怀模块
│       │       │   │   ├── ProactiveTriggers.kt         # 触发类型/阈值/数据结构
│       │       │   │   ├── ProactiveConfig.kt           # 配置（开关/阈值/冷却/上限）
│       │       │   │   ├── ProactiveDecisionEngine.kt   # 决策引擎（规则+概率过滤）
│       │       │   │   ├── ProactiveContentGenerator.kt # 内容生成（LLM动态文案）
│       │       │   │   ├── ProactiveCareRepository.kt   # 关怀历史记录仓储
│       │       │   │   ├── ProactiveCareViewModel.kt    # 主动关怀配置UI VM
│       │       │   ├── persona/           # 人格模块
│       │       │   │   ├── PersonaManager.kt            # 人格管理器（激活/切换/SystemPrompt）
│       │       │   │   ├── PersonaRepository.kt         # 人格数据库仓储
│       │       │   │   ├── PersonaViewModel.kt          # 人格界面VM
│       │       │   │   ├── PersonaWorkshopScreen.kt     # 工坊编辑UI
│       │       │   │   └── PersonaWorkshopActivity.kt   # 工坊Activity
│       │       │   ├── mod/               # Mod模块
│       │       │   │   ├── ModManager.kt                # Mod加载/启用/禁用管理
│       │       │   │   ├── ModRepository.kt             # Mod本地仓储
│       │       │   │   └── ScriptEngine.kt              # Mod脚本引擎
│       │       │   ├── knowledge/         # 知识库RAG模块
│       │       │   │   ├── DocumentProcessor.kt         # 文档解析+切片
│       │       │   │   ├── EmbeddingProvider.kt         # 向量化抽象（Ollama实现）
│       │       │   │   ├── VectorStore.kt               # 向量库接口+InMemory实现
│       │       │   │   ├── KnowledgeManager.kt          # 知识库管理器
│       │       │   │   └── KnowledgeViewModel.kt        # 知识库UI VM
│       │       │   ├── health/            # 健康模块
│       │       │   │   ├── HealthManager.kt             # Health Connect+Sensor聚合
│       │       │   │   ├── HealthRepository.kt          # 健康数据仓储
│       │       │   │   ├── HealthPanelScreen.kt         # 健康看板UI
│       │       │   │   └── HealthViewModel.kt           # 健康看板VM
│       │       │   ├── floating/          # 悬浮窗模块
│       │       │   │   ├── FloatingBubbleView.kt        # 气泡View（绘制+触摸）
│       │       │   │   ├── FloatingBubbleManager.kt     # 悬浮窗WindowManager管理
│       │       │   │   └── FloatingViewModel.kt         # 悬浮窗VM
│       │       │   ├── chat/              # 主聊天模块
│       │       │   │   ├── ChatScreen.kt                # 聊天界面Compose
│       │       │   │   └── ChatViewModel.kt             # 聊天VM
│       │       │   ├── community/         # 创意工坊社区模块
│       │       │   │   ├── CommunityScreen.kt           # 工坊浏览UI
│       │       │   │   ├── CommunityActivity.kt         # 工坊Activity
│       │       │   │   └── CommunityViewModel.kt        # 工坊VM
│       │       │   ├── script/            # 脚本工坊模块
│       │       │   │   ├── ScriptWorkshopScreen.kt      # 脚本编辑UI
│       │       │   │   ├── ScriptWorkshopActivity.kt    # 脚本Activity
│       │       │   │   └── ScriptViewModel.kt           # 脚本VM
│       │       │   ├── onboarding/        # 首次引导模块
│       │       │   │   ├── OnboardingScreen.kt          # 4步引导Compose
│       │       │   │   └── PermissionGuideActivity.kt   # 权限引导Activity
│       │       │   └── settings/          # 设置模块
│       │       │       ├── SettingsScreen.kt            # 设置主界面UI
│       │       │       └── SettingsActivity.kt          # 设置Activity
│       │       ├── services/              # Android Service/Receiver
│       │       │   ├── VoiceAssistantService.kt         # 语音助手前台Service
│       │       │   ├── WakeWordService.kt               # 唤醒词后台监听Service
│       │       │   ├── LingShuAccessibilityService.kt   # 无障碍Service（全局操作）
│       │       │   ├── NotificationListener.kt          # 通知监听Service
│       │       │   ├── BootCompletedReceiver.kt         # 开机自启动广播
│       │       │   └── ConnectivityReceiver.kt          # 网络状态变化广播
│       │       ├── ui/                  # UI通用主题与组件
│       │       │   ├── theme/
│       │       │   │   ├── GlassmorphismTheme.kt    # 玻璃磨砂主题定义（配色/形状/字体）
│       │       │   │   ├── GlassComponents.kt       # GlassCard/GlassButton可复用组件
│       │       │   │   └── Animation.kt             # 通用动画（淡入/滑入/震动）
│       │       │   └── components/
│       │       │       └── GlassComponents.kt       # UI通用组件
│       │       └── utils/               # 工具类
│       │           ├── CryptoHelper.kt              # AES-256加解密（AndroidKeyStore）
│       │           ├── FileHelper.kt                # 文件/目录/ZIP操作工具
│       │           └── PermissionHelper.kt          # 权限申请+结果封装工具
│       └── test/java/com/lingshu/agent/   # 本地JVM单元测试
│           ├── core/model/MessageTest.kt            # Message数据类测试
│           ├── feature/voice/VADTest.kt             # VAD状态机测试
│           ├── feature/model/ModelRouterTest.kt     # 模型路由优先级/降级测试
│           ├── feature/persona/PersonaEvolutionTest.kt # 人格演化算法测试
│           ├── feature/proactive/ProactiveDecisionEngineTest.kt # 主动决策规则测试
│           ├── feature/mod/ModManifestValidationTest.kt # Mod manifest校验测试
│           └── feature/knowledge/VectorStoreTest.kt # 向量库余弦相似度测试
└── gradle/wrapper/                      # Gradle Wrapper（8.5+）
```

---

## 5. 开源参考项目

| 序号 | 参考项目名称 | 用途说明 |
|------|-------------|---------|
| 1 | **Cherry Studio** | 多模型AI客户端UI交互设计参考，多Provider路由架构参考 |
| 2 | **Open WebUI** | Ollama集成与前端交互参考，模型管理界面参考 |
| 3 | **Raspberry-Pi-AI-Voice-Assistant** | 唤醒词+VAD+STT+TTS全链路语音交互参考 |
| 4 | **Tasker (AutoApps)** | Android自动化与JS脚本引擎设计参考，插件系统架构参考 |
| 5 | **Termux** | Android本地运行时环境与DexClassLoader动态加载参考 |
| 6 | **Android Accessibility Suite (Google)** | 无障碍高级操作（点击/滑动/手势）API用法参考 |
| 7 | **Home Assistant Android** | 传感器数据采集+后台保活+WorkManager任务调度参考 |
| 8 | **Obsidian Mobile** | 本地Markdown文档解析与RAG知识管理体验参考 |
| 9 | **K9 Mail / Thunderbird Android** | 大型Kotlin+Compose项目Clean Architecture分层参考 |

---

## 6. 开发路线图

| 阶段 | 代号 | 核心内容 | 预估时间 |
|------|------|---------|---------|
| **P0** | 地基 | 项目脚手架搭建、Hilt/Room/Compose/Navigation基础框架跑通、主题系统实现 | 1周 |
| **P1** | 对话 | 聊天界面+ModelProvider抽象+首个Provider(DeepSeek)+流式响应 | 1.5周 |
| **P2** | 语音 | 唤醒词+STT+TTS+VAD+全双工语音会话打通 | 2周 |
| **P3** | 模型路由 | 多Provider接入(GPT-4V/Ollama/Vosk/TTS) + 路由优先级+降级 | 1.5周 |
| **P4** | 控制 | 系统API控制+无障碍控制+截图+屏幕理解+JS脚本引擎 | 2.5周 |
| **P5** | 人格 | 大五维度模型+反馈演化算法+PersonaManager+人格工坊UI+导入导出 | 1.5周 |
| **P6** | 主动 | 决策引擎5种触发+冷却/上限/概率过滤+LLM内容生成 | 2周 |
| **P7** | 悬浮 | 悬浮窗气泡+拖拽吸附+快捷对话+状态动画 | 1周 |
| **P8** | Mod | .lspack规范+动态加载+启用禁用+创意工坊基础UI | 2周 |
| **P9** | 健康 | Health Connect+SensorManager+异常报警+习惯分析看板 | 1.5周 |
| **P10** | RAG | 文档切片+Ollama向量化+InMemoryVectorStore+检索增强对话 | 2周 |
| **P11** | UI完善 | 10个界面补全+玻璃磨砂优化+空态/错误态/加载动画+首次引导 | 2周 |
| **P12** | 交付 | APK体积优化<60MB+混淆+签名+OTA更新+单元测试覆盖率+文档 | 1.5周 |

**总计预估：约 24 周（6个月），按单开发人力估算**

---

## 7. 编译与运行指南

### 7.1 环境要求

| 工具 | 最低版本 | 说明 |
|------|---------|------|
| Android Studio | Hedgehog (2023.1.1) + | 推荐Iguana/Jellyfish，原生支持Compose |
| JDK | 17 + | 项目compileOptions已配置JDK17，Android Studio内置即可 |
| Gradle | 8.5 + | Gradle Wrapper已封装，无需手动安装 |
| Android SDK | Platform 34 | compileSdk 34，SDK Manager下载 |
| 测试设备 / 模拟器 | Android 7.0 (API 24) + | 推荐真机测试（真机有语音/传感器/无障碍） |

### 7.2 克隆仓库

```bash
git clone https://github.com/lingshu-ai/lingshu-android.git
cd lingshu-android
```

### 7.3 本地配置

**(1) local.properties（项目根目录自动生成或手动创建）**

```properties
# Android SDK路径（必选）
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk

# 可选：自定义签名配置（Release构建使用）
storeFile=../keystore/lingshu-release.jks
storePassword=your_store_password
keyAlias=lingshu_release
keyPassword=your_key_password
```

**(2) 签名配置说明**
- Debug构建：使用Android SDK默认debug keystore，无需额外配置
- Release构建：在`app/build.gradle.kts`中配置signingConfigs，从local.properties读取签名参数
- APK大小优化：仅保留`arm64-v8a` ABI（如需armeabi-v7a/x86_64可手动添加）

### 7.4 构建命令

Windows PowerShell / CMD：
```powershell
# Debug构建（开发调试用）
.\gradlew.bat assembleDebug

# Release构建（分发用，需签名配置）
.\gradlew.bat assembleRelease

# 运行单元测试
.\gradlew.bat testDebugUnitTest

# 查看所有构建任务
.\gradlew.bat tasks
```

Linux / macOS：
```bash
# Debug构建
./gradlew assembleDebug

# Release构建
./gradlew assembleRelease

# 运行单元测试
./gradlew testDebugUnitTest
```

构建产物路径：
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

### 7.5 安装到设备

```bash
# 确认设备已连接（USB调试开启或模拟器运行）
adb devices

# 安装Debug包（覆盖安装 -r）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 安装Release包
adb install -r app/build/outputs/apk/release/app-release.apk

# 从命令行启动应用
adb shell am start -n com.lingshu.agent/.MainActivity
```

### 7.6 首次启动流程

| 步骤 | 页面 | 内容 |
|------|------|------|
| **Step 1** | 欢迎页 | Logo动画+灵枢简介+「开始使用」按钮 |
| **Step 2** | 权限引导 | 列出所需权限（麦克风/通知/悬浮窗/无障碍/位置），每项跳转设置并回传结果 |
| **Step 3** | 模型配置 | 选择并配置默认模型：输入DeepSeek API Key 或 配置Ollama地址；设置默认对话/视觉/STT/TTS模型 |
| **Step 4** | 人格选择 | 5种预设人格（温柔/幽默/犀利/沉稳/自定义）+ 人格工坊快速编辑 + 导入人格JSON |

完成以上4步后进入主聊天界面，同时启动：
- 唤醒词后台监听服务
- 主动关怀定时任务
- 悬浮窗（权限已授予时）

---

## 8. 验收标准对照表

| 序号 | 验收标准 | 代码入口类 / 函数 | 状态 |
|------|---------|------------------|------|
| 1 | 语音唤醒→识别→对话→合成全闭环 | `feature/voice/VoiceSession.kt` 状态机 + `services/VoiceAssistantService.kt` | ✅ |
| 2 | 多模型路由优先级+自动降级 | `feature/model/ModelRouter.kt` → `buildCandidateProviderList()` / `executeRoutedTask()` | ✅ |
| 3 | 系统API控制+无障碍控制+JS脚本 | `feature/control/SystemController.kt` / `AccessibilityController.kt` / `ScriptEngine.kt` | ✅ |
| 4 | 5种主动触发+冷却+上限+概率过滤 | `feature/proactive/ProactiveDecisionEngine.kt` → `shouldTrigger()` | ✅ |
| 5 | 大五人格演化+LIKED/DISLIKED反馈+夹紧 | `core/model/Persona.kt` → `BigFiveTraits.adjust()` + `clamp()` | ✅ |
| 6 | .lspack格式+manifest校验+动态加载 | `feature/mod/ModManager.kt` 解析 + manifest字段校验 | ✅ |
| 7 | Health Connect+传感器+异常报警 | `feature/health/HealthManager.kt` → 异常心率检查逻辑 | ✅ |
| 8 | 文档切片→向量化→检索→RAG对话 | `feature/knowledge/DocumentProcessor.kt` + `VectorStore.kt` → `searchByVector()` | ✅ |
| 9 | 悬浮窗气泡+拖拽+快捷对话 | `feature/floating/FloatingBubbleView.kt` + `FloatingBubbleManager.kt` | ✅ |
| 10 | 10个界面玻璃磨砂Dark Glassmorphism | `ui/theme/GlassmorphismTheme.kt` + 各Feature下的`*Screen.kt` | ✅ |
| 11 | AES-256加密+本地优先+一键清除+JSON导出 | `utils/CryptoHelper.kt` + `AppSettingsDataStore.kt` + 设置页清除按钮 | ✅ |
| 12 | APK<60MB+首次4步引导+GitHub Releases OTA | `app/build.gradle.kts` splits + `feature/onboarding/OnboardingScreen.kt` | ✅ |

---

## 9. 特别约束说明

### 9.1 第三方依赖约束
- ✅ **仅依赖需求中明确列出的库**：Compose / Hilt / Room / DataStore / Retrofit / OkHttp / Coroutines / WorkManager / Accompanist Permissions / Coil / Health Connect
- ❌ **禁止引入非Android官方/非列表库**（如：Guava、RxJava、Fastjson、Glide等均不允许）
- ✅ OkHttp/Retrofit是业界标准HTTP客户端，已在需求列表中明确允许

### 9.2 网络超时配置
```kotlin
// app/src/main/java/com/lingshu/agent/core/network/RetrofitClient.kt
OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)   // 连接超时 10秒
    .readTimeout(30, TimeUnit.SECONDS)      // 读取超时 30秒
    .writeTimeout(30, TimeUnit.SECONDS)     // 写入超时 30秒
    .build()
```

### 9.3 权限策略
- 所有危险权限（麦克风/位置/通知/存储等）：运行时动态申请
- 特殊权限（悬浮窗/无障碍/通知监听）：引导页跳转设置页 + 回调检测
- 每个权限附"用途说明"透明展示，用户可随时撤销

### 9.4 模型切换即时生效
- `ModelSettings`变更 → `MutableStateFlow`推送 → `ModelRouter`下次路由直接读取最新值
- `switchProvider()`手动锁定 → `_currentProviderFlow`即时更新，UI绑定自动刷新
- 无需重启应用 / 无需重新初始化任何组件

### 9.5 模块独立可测试
- 每个Feature包独立：`voice/`、`model/`、`control/`、`persona/` 等互不直接new对象，全部通过Hilt注入
- 核心逻辑与Android SDK解耦：`VAD`、`ModelRouter`、`ProactiveDecisionEngine`、`VectorStore`均为纯Kotlin类，可直接JVM单测
- 数据层Repository抽象，便于Fake替换

### 9.6 API 24+ 兼容性
- 所有`>API24`的新API使用前均做版本检查：`if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.XXX)`
- 高版本特性有低版本fallback替代方案
- `compileSdk=34` 但 `minSdk=24` 全部功能可用

### 9.7 APK体积控制策略
1. **ABI过滤**：仅打包 `arm64-v8a`（覆盖99%现役Android设备）
2. **R8混淆**：`isMinifyEnabled = true` + 代码优化 + 资源压缩
3. **依赖精简**：不引入无用库，Compose BOM只import用到的artifact
4. **资源剔除**：`resConfigs` 仅保留中文+英文资源，删除未使用drawable
5. **WebP转换**：所有位图资源转WebP有损压缩
6. **未用资源**：`shrinkResources = true` 自动剥离无用资源

---

## 10. 安全说明 & 许可证

### 10.1 安全说明

**🔒 数据加密**
- API密钥、聊天记录、人格记忆、健康数据等敏感信息：AES-256-GCM加密存储
- 密钥通过AndroidKeyStore生成并保管，无法被提取，设备重启后仍有效
- 加密算法实现见：`utils/CryptoHelper.kt`

**📱 权限最小化**
- 只申请必要权限：麦克风（语音交互）、通知（关怀提醒）、悬浮窗（快捷入口）、无障碍（高级控制）、前台服务（后台保活）
- 不申请：通讯录、短信、通话记录、摄像头（视觉识别时临时申请）、精确位置（按需授权）

**☁️ 本地优先**
- 所有默认数据仅存本地，不上传任何服务器
- 模型API请求只发送必要上下文，用户可随时查看/取消发送
- 云端同步功能：默认关闭，手动开启后才上传

**🧪 敏感操作二次确认**
- 发送短信、拨打电话、支付转账、卸载应用、清空数据等高危操作 → 必须弹窗二次确认
- 可设置"敏感操作白名单App"简化流程

### 10.2 许可证

```
灵枢（Ling Shu）Android AI Agent
Copyright (C) 2025 Ling Shu Team

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

附加声明：
1. 本软件仅用于学习、研究和个人使用。
2. 使用本软件进行的任何操作由用户自行承担风险。
3. 使用第三方模型API时，请遵守对应服务商的服务条款。
4. Mod/创意工坊内容由社区贡献，版权归贡献者所有。
```
