# TimeRecorder - Android 时间记录应用

这是一个Android版本的时间记录应用，用于记录和可视化一天中的活动时间。

## 功能特点

- **多槽位并行计时**: 同时记录多个进行中的活动，卡片式计时；进程被杀后由 AlarmManager 兜底恢复，槽位状态持久化
- **竖向时间轴**: 24 小时竖向时间轴，活动块按重叠自动分层、支持垂直滚动与双指缩放、红色"现在"时刻线
- **活动编辑**: 点击活动项编辑名称/起止时间/动机，长按删除；改成跨天自动按天拆分
- **活动动机**: 每个活动可记录"动机"（为什么做），编辑对话框中维护；AI 总结与打分时作为参考维度
- **AI 日程助手**: 对话式查询/新增/删除日程，Function Calling 驱动；回复偏向生活引导建议；支持用户自定义 AI 风格/人设
- **每日总结 + 打分**: AI 按固定标准（作息规律/时间专注/劳逸结合/动机契合）给每天 0-100 评分并存库，显示在活动记录页顶部卡片，活动变更自动重建
- **评分视图**: 新增"评分"Tab，本周/本月评分热力格（按分数着色）+ 平均分，点击查看当天评语
- **跨天自动分割**: 开始/结束跨过午夜时自动按天拆分为多条记录（支持连续多天），并归一化日期保证查询稳定
- **数据持久化**: SQLite 存储活动与每日总结（数据库 v3：`activities` + `daily_summaries`）

## 项目结构

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/timerecorder/
│   │   │   ├── MainActivity.java                    # 主页：Tab 页驱动 + 数据加载 + 总结触发
│   │   │   ├── adapter/
│   │   │   │   ├── ViewPagerAdapter.java            # 3 个 Tab（时间轴/活动记录/评分）
│   │   │   │   └── ChatAdapter.java                 # AI 对话消息列表
│   │   │   ├── ai/
│   │   │   │   ├── ChatActivity.java                # AI 助手聊天页（function calling 循环）
│   │   │   │   ├── OpenAiClient.java                # OpenAI 兼容 HTTP 客户端
│   │   │   │   ├── AiConfig.java                    # API 配置 + 自定义风格（AiPrefs）
│   │   │   │   ├── ToolExecutor.java                # 日程工具执行（增删查 + 每日总结查询）
│   │   │   │   └── SummaryGenerator.java            # 每日总结+打分生成（异步/幂等/串行）
│   │   │   ├── database/
│   │   │   │   └── DatabaseHelper.java              # SQLite 助手（v3，含每日总结表）
│   │   │   ├── fragment/
│   │   │   │   ├── TimeAxisFragment.java            # 时间轴页
│   │   │   │   ├── ActivityRecordFragment.java      # 活动记录页（总结卡片/计时槽位/活动列表）
│   │   │   │   └── ScoreFragment.java               # 评分页（周/月热力格）
│   │   │   ├── model/
│   │   │   │   ├── Activity.java                    # 活动（含 motive）
│   │   │   │   ├── DailySummary.java                # 每日总结（date/score/summary）
│   │   │   │   ├── TimerSlot.java                   # 计时槽位
│   │   │   │   ├── TimerSlotStore.java              # 槽位持久化（SharedPreferences）
│   │   │   │   └── ChatMessage.java                 # 聊天消息
│   │   │   ├── service/
│   │   │   │   ├── TimerService.java                # 后台计时兜底服务
│   │   │   │   └── TimerReceiver.java               # AlarmManager 兜底闹钟
│   │   │   └── view/
│   │   │       └── VerticalTimeAxisView.java        # 竖向时间轴自绘视图
│   │   ├── res/
│   │   │   ├── layout/    # activity_main、activity_chat、fragment_*、dialog_*、item_*
│   │   │   ├── values/    # colors.xml、strings.xml、themes.xml
│   │   │   └── drawable/  # 图标与背景形状
│   │   ├── build.gradle
│   │   └── AndroidManifest.xml
│   ├── mykey.keystore     # release 发布签名
│   └── build.bat          # 一键打包脚本
├── build.gradle
├── settings.gradle
└── README.md
```

## 技术栈

- Java（原生开发，无业务第三方库）
- Android SDK 34（minSdk 24 / targetSdk 34）
- Material Design Components
- SQLite（SQLiteOpenHelper，非 Room）
- OpenAI 兼容 API（HttpURLConnection + org.json，Function Calling 驱动日程工具）
- ViewPager2 + TabLayout + RecyclerView

## 编译和运行

### 使用 Android Studio

1. 打开 Android Studio
2. 选择 "Open an existing Android Studio project"
3. 选择 `timerecorder/android` 文件夹
4. 等待 Gradle 同步完成
5. 点击运行按钮（或按 Shift+F10）

### 使用命令行

```bash
cd android
./gradlew assembleDebug
```

生成的APK文件位于 `app/build/outputs/apk/debug/app-debug.apk`

> 注：本项目**没有 Gradle wrapper**，命令行编译需使用本机安装的 gradle 8.5，并设置 JDK21（见下文"打包发布 APK"）。

## 打包发布 APK

### 一键打包

双击 `android/build.bat` 即可打出**签名发布包**（脚本已内置 JDK21 与离线缓存配置，无需敲命令）。

### 命令行打包

```bash
cd d:/MyProject/timerecorder/android
GRADLE_USER_HOME="D:/Android/.gradle" \
JAVA_HOME="C:\Users\Squirrelxzt\jdk21\jdk-21.0.12+8" \
~/.gradle/wrapper/dists/gradle-8.5-bin/5hry6tgzq0wontdz18qo6fdj9/gradle-8.5/bin/gradle \
assembleRelease --offline
```

要点：

- `JAVA_HOME` 必须指向 **JDK21**：用 JDK25 会报 `Unsupported class file major version 69` 错误
- `--offline` 离线编译，依赖已全部缓存，无需联网
- `GRADLE_USER_HOME` 指向 `D:/Android/.gradle`，存放离线依赖缓存

### 产物

| 命令 | APK 路径 | 说明 |
|---|---|---|
| `assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | **发布包**：正式签名、`debuggable=false`，可直接安装/分发 |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | 调试包：仅本机调试签名，其他设备装不了，**勿对外分发** |

### 发布注意

- 正式分发前在 `app/build.gradle` 的 `defaultConfig` 中递增 `versionCode` 并更新 `versionName`
- 当前 `minifyEnabled false`（不混淆），个人项目体积小且无混淆运行风险，够用；APK 变大后再考虑开启 R8
- Release 签名：`app/mykey.keystore`（alias `mykey`），发布包签名已配置在 `build.gradle`，apksigner 可验证
- 覆盖安装需保证签名一致（始终用同一个 keystore 打包）

## 使用方法

1. 在"活动名称"输入框中输入活动名称
2. 设置开始时间（小时和分钟）
3. 点击"开始活动"按钮
4. 活动结束后，设置结束时间
5. 点击"结束活动"按钮
6. 活动将自动显示在时间轴上，并保存到数据库
7. 长按活动列表中的项可删除活动
