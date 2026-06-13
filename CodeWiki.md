# NotesMaster (MiCode Notes) —— Code Wiki

## 1. 项目概述

**NotesMaster** 是一款源自小米 MiCode 开源社区（www.micode.net）的 Android 便签应用。该应用提供了完整的便签管理功能，包括文本笔记、通话记录笔记、文件夹管理、桌面小组件、闹钟提醒、全文搜索、SD 卡导出以及通过 Google Tasks API 进行云端同步等功能。

| 属性 | 值 |
|---|---|
| **项目名称** | Notes-master (NotesMaster) |
| **包名** | `net.micode.notes` |
| **语言** | Java 11 |
| **构建系统** | Gradle (Kotlin DSL) |
| **最低 SDK** | 24 (Android 7.0) |
| **目标 SDK** | 36 |
| **数据库** | SQLite (note.db, version 4) |
| **许可证** | Apache License 2.0 |
| **根目录** | `d:\CODE\Notesmaster\` |

---

## 2. 项目整体架构

### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer (ui/)                          │
│  NotesListActivity  NoteEditActivity  NoteEditText          │
│  NotesListAdapter   NoteItemData      AlarmAlertActivity    │
│  DateTimePicker     DropdownMenu      NotesPreferenceActivity│
├─────────────────────────────────────────────────────────────┤
│                    Model Layer (model/)                      │
│         Note (数据持久化模型)                                  │
│         WorkingNote (运行时工作模型)                           │
├─────────────────────────────────────────────────────────────┤
│                     Tool Layer (tool/)                       │
│    DataUtils  BackupUtils  ResourceParser  GTaskStringUtils  │
├───────────────────────┬─────────────────────────────────────┤
│    Data Layer (data/)  │     GTask Sync Layer (gtask/)      │
│  Notes (Contract)      │  GTaskManager (同步编排)            │
│  NotesDatabaseHelper   │  GTaskClient (HTTP 客户端)          │
│  NotesProvider (CP)    │  GTaskSyncService (Service)        │
│  Contact               │  GTaskASyncTask (AsyncTask)        │
│                        │  Node/Task/TaskList/MetaData (模型) │
│                        │  SqlNote/SqlData (本地↔远程桥接)    │
├───────────────────────┴─────────────────────────────────────┤
│                  Widget Layer (widget/)                      │
│    NoteWidgetProvider  NoteWidgetProvider_2x/4x              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 包结构总览

```
net.micode.notes
├── data/                数据层：数据库定义、ContentProvider、联系人查询
├── model/               模型层：便签数据模型与运行时工作模型
├── tool/                工具层：数据操作、备份导出、资源解析、GTask 字符串常量
├── ui/                  UI 层：Activity、Adapter、自定义 View、闹钟、日期选择器
├── widget/              桌面小组件层
└── gtask/               Google Tasks 同步层
    ├── data/            同步数据模型
    ├── exception/       同步异常定义
    └── remote/          远程同步管理器与 HTTP 客户端
```

---

## 3. 依赖关系

### 3.1 外部依赖

根据 [build.gradle.kts](file:///d:/CODE/Notesmaster/app/build.gradle.kts) 和 [libs.versions.toml](file:///d:/CODE/Notesmaster/gradle/libs.versions.toml)：

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.appcompat:appcompat` | 1.6.1 | AndroidX 兼容库 |
| `com.google.android.material:material` | 1.10.0 | Material Design 组件 |
| `org.apache.httpcomponents:httpclient-osgi` | 4.5.14 | Google Tasks API 的 HTTP 通信 |
| `org.apache.httpcomponents:httpclient-win` | 4.5.14 | Windows 平台的 HTTP 支持 |
| `org.apache.httpcomponents:httpcore` | 4.4.16 | HTTP 核心库 |

**测试依赖：**
- `junit:junit` 4.13.2
- `androidx.test.ext:junit` 1.1.5
- `androidx.test.espresso:espresso-core` 3.5.1

### 3.2 模块间依赖关系

```
ui ────────────────► model ────────────────► data
 │                     │                       │
 │                     ▼                       │
 ├─────────────────► tool ◄────────────────────┤
 │                     ▲                       │
 │                     │                       │
 ├─────────────────► gtask ◄───────────────────┤
 │                     │                       │
 └─────────────────► widget                    │
```

- **UI 层** 依赖 model、data、tool、gtask、widget
- **Model 层** 依赖 data
- **GTask 层** 依赖 data、tool
- **Tool 层** 依赖 data、ui（部分）
- **Widget 层** 依赖 data、tool、ui

---

## 4. 数据层详解 (net.micode.notes.data)

### 4.1 Notes.java —— 数据契约类

[Notes.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/data/Notes.java)

定义整个应用的数据规范，包含：

**核心常量：**
- `AUTHORITY = "micode_notes"` —— ContentProvider 授权标识
- `TYPE_NOTE = 0`, `TYPE_FOLDER = 1`, `TYPE_SYSTEM = 2` —— 条目类型
- 系统文件夹 ID：
  - `ID_ROOT_FOLDER = 0` —— 根文件夹
  - `ID_TEMPARAY_FOLDER = -1` —— 临时文件夹
  - `ID_CALL_RECORD_FOLDER = -2` —— 通话记录文件夹
  - `ID_TRASH_FOLER = -3` —— 回收站文件夹

**内部接口：**

| 接口 | 说明 |
|---|---|
| `NoteColumns` | 定义 note 表的 17 个字段（ID、PARENT_ID、SNIPPET、TYPE、GTASK_ID、VERSION 等） |
| `DataColumns` | 定义 data 表的 10 个字段（ID、MIME_TYPE、NOTE_ID、CONTENT、DATA1~DATA5 等） |
| `DataConstants` | 数据 MIME 类型常量（`NOTE`、`CALL_NOTE`） |

**内部类：**

| 类 | 说明 |
|---|---|
| `TextNote` | 文本笔记的数据定义，包含 `MODE`（清单模式）字段 |
| `CallNote` | 通话记录笔记的数据定义，包含 `CALL_DATE`、`PHONE_NUMBER` 字段 |

**URI 定义：**
- `CONTENT_NOTE_URI` → `content://micode_notes/note`
- `CONTENT_DATA_URI` → `content://micode_notes/data`

### 4.2 NotesDatabaseHelper.java —— 数据库管理

[NotesDatabaseHelper.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/data/NotesDatabaseHelper.java)

继承 `SQLiteOpenHelper`，管理数据库 `note.db`（当前版本 4）。

**数据库表结构：**

**NOTE 表（17 列）：**

| 列名 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `_id` | INTEGER PK | — | 主键 |
| `parent_id` | INTEGER | 0 | 父文件夹 ID |
| `alert_date` | INTEGER | 0 | 提醒时间 |
| `bg_color_id` | INTEGER | 0 | 背景色 ID |
| `created_date` | INTEGER | now*1000 | 创建时间 |
| `has_attachment` | INTEGER | 0 | 是否有附件 |
| `modified_date` | INTEGER | now*1000 | 修改时间 |
| `notes_count` | INTEGER | 0 | 文件夹内笔记数 |
| `snippet` | TEXT | '' | 摘要/文件夹名 |
| `type` | INTEGER | 0 | 类型（0=笔记 1=文件夹 2=系统） |
| `widget_id` | INTEGER | 0 | 关联小组件 ID |
| `widget_type` | INTEGER | -1 | 小组件类型 |
| `sync_id` | INTEGER | 0 | 上次同步 ID |
| `local_modified` | INTEGER | 0 | 本地修改标记 |
| `origin_parent_id` | INTEGER | 0 | 原始父文件夹 ID |
| `gtask_id` | TEXT | '' | Google Task ID |
| `version` | INTEGER | 0 | 乐观锁版本号 |

**DATA 表（10 列）：**

| 列名 | 类型 | 说明 |
|---|---|---|
| `_id` | INTEGER PK | 主键 |
| `mime_type` | TEXT | MIME 类型 |
| `note_id` | INTEGER | 关联 note._id |
| `created_date` | INTEGER | 创建时间 |
| `modified_date` | INTEGER | 修改时间 |
| `content` | TEXT | 内容 |
| `data1` | INTEGER | 通用整数字段 |
| `data2` | INTEGER | 通用整数字段 |
| `data3` | TEXT | 通用文本字段 |
| `data4` | TEXT | 通用文本字段 |
| `data5` | TEXT | 通用文本字段 |

**数据库触发器（共 10 个）：**

| 触发器 | 触发条件 | 功能 |
|---|---|---|
| `increase_folder_count_on_update` | NOTE.PARENT_ID 更新 | 目标文件夹笔记数 +1 |
| `decrease_folder_count_on_update` | NOTE.PARENT_ID 更新 | 源文件夹笔记数 -1 |
| `increase_folder_count_on_insert` | NOTE 插入 | 父文件夹笔记数 +1 |
| `decrease_folder_count_on_delete` | NOTE 删除 | 父文件夹笔记数 -1 |
| `update_note_content_on_insert` | DATA 插入（NOTE 类型） | 同步 snippet 内容 |
| `update_note_content_on_update` | DATA 更新（NOTE 类型） | 同步 snippet 内容 |
| `update_note_content_on_delete` | DATA 删除（NOTE 类型） | 清空 snippet |
| `delete_data_on_delete` | NOTE 删除 | 级联删除关联 data |
| `folder_delete_notes_on_delete` | NOTE（文件夹）删除 | 级联删除子笔记 |
| `folder_move_notes_on_trash` | NOTE.PARENT_ID → 回收站 | 子笔记也移入回收站 |

**系统文件夹初始化：**
自动创建 4 个系统文件夹：通话记录文件夹、根文件夹、临时文件夹、回收站文件夹。

**数据库升级路径：**
- V1 → V2：删除并重建所有表
- V2 → V3：新增 `gtask_id` 列 + 回收站文件夹
- V3 → V4：新增 `version` 列（乐观锁）

### 4.3 NotesProvider.java —— ContentProvider

[NotesProvider.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/data/NotesProvider.java)

提供对 `note` 和 `data` 两张表的 CRUD 操作，使用 `UriMatcher` 匹配 5 种 URI 模式：

| URI 模式 | 匹配码 | 说明 |
|---|---|---|
| `content://micode_notes/note` | URI_NOTE (1) | 批量操作 note |
| `content://micode_notes/note/#` | URI_NOTE_ITEM (2) | 单条 note |
| `content://micode_notes/data` | URI_DATA (3) | 批量操作 data |
| `content://micode_notes/data/#` | URI_DATA_ITEM (4) | 单条 data |
| `content://micode_notes/search` | URI_SEARCH (5) | 全文搜索 |
| `content://micode_notes/search_suggest_query/*` | URI_SEARCH_SUGGEST (6) | 搜索建议 |

**关键方法：**
- `query()` —— 支持搜索功能，使用 LIKE 匹配 snippet 字段，排除回收站条目
- `insert()` —— 插入后通过 `notifyChange()` 通知 UI 刷新
- `update()` —— 自动调用 `increaseNoteVersion()` 递增版本号
- `delete()` —— 禁止删除 ID ≤ 0 的系统文件夹

### 4.4 Contact.java —— 联系人查询

[Contact.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/data/Contact.java)

通过 `ContactsContract` API 根据电话号码查询联系人姓名，使用 HashMap 缓存已查询的结果。

---

## 5. 模型层详解 (net.micode.notes.model)

### 5.1 Note.java —— 数据持久化模型

[Note.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/model/Note.java)

封装对数据库的写操作。每次属性变更时记录到 `ContentValues` 差分集合中。

**关键方法：**

| 方法 | 说明 |
|---|---|
| `getNewNoteId(context, folderId)` | 静态方法，创建新笔记并返回 ID |
| `setNoteValue(key, value)` | 设置 note 表字段，自动标记 `LOCAL_MODIFIED=1` |
| `setTextData(key, value)` | 设置文本数据字段 |
| `setCallData(key, value)` | 设置通话记录数据字段 |
| `isLocalModified()` | 检查是否有未同步的本地修改 |
| `syncNote(context, noteId)` | 将差分数据写入 ContentProvider |

**内部类 NoteData：**
管理文本数据（`mTextDataValues`）和通话数据（`mCallDataValues`）两套差分值。`pushIntoContentResolver()` 使用 `ContentProviderOperation` 批量写入。

### 5.2 WorkingNote.java —— 运行时工作模型

[WorkingNote.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/model/WorkingNote.java)

编辑会话期间维护的便签模型，封装 Note 对象并提供变更监听。

**构造函数：**
- `createEmptyNote(context, folderId, ...)` —— 新建空白笔记
- `load(context, id)` —— 从数据库加载已有笔记

**变更追踪：**
- 属性变更时调用 `mNote.setNoteValue()` 和 `mNote.setTextData()` 记录差分
- `saveNote()` 检查 `isWorthSaving()`，条件：未被删除、内容非空或有本地修改

**变更监听接口 `NoteSettingChangedListener`：**

| 回调 | 触发场景 |
|---|---|
| `onBackgroundColorChanged()` | 背景色变更 |
| `onClockAlertChanged(date, set)` | 闹钟设置/取消 |
| `onWidgetChanged()` | 小组件内容变更 |
| `onCheckListModeChanged(old, new)` | 清单/普通模式切换 |

---

## 6. UI 层详解 (net.micode.notes.ui)

### 6.1 NotesListActivity.java —— 主列表界面

[NotesListActivity.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/ui/NotesListActivity.java)

应用的启动 Activity，负责展示笔记和文件夹列表。

**状态机（ListEditState）：**

```
NOTE_LIST ←──► SUB_FOLDER ←──► CALL_RECORD_FOLDER
```

**核心功能：**
- **文件夹导航** —— 点击文件夹进入子目录，按返回键回到上级
- **新建笔记** —— 通过 `Intent.ACTION_INSERT_OR_EDIT` 启动 `NoteEditActivity`
- **多选模式（ModeCallback）** —— 长按笔记进入 ActionMode，支持全选、批量删除、批量移动
- **文件夹管理** —— 长按文件夹弹出上下文菜单（查看、删除、重命名）
- **导出为文本** —— 通过 `BackupUtils` 异步导出所有笔记到 SD 卡
- **同步控制** —— 通过 `GTaskSyncService` 启动/取消 Google Tasks 同步
- **首次使用引导** —— 从 `raw/introduction` 读取介绍文本创建第一条笔记
- **搜索** —— 支持系统搜索框架

**后台查询（BackgroundQueryHandler）：**
使用 `AsyncQueryHandler` 在后台线程执行 ContentProvider 查询，避免阻塞 UI 线程。

**删除策略：**
- 非同步模式：物理删除
- 同步模式：移入回收站文件夹

### 6.2 NoteEditActivity.java —— 笔记编辑界面

[NoteEditActivity.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/ui/NoteEditActivity.java)

实现笔记的创建、编辑、删除等核心编辑功能。

**Intent 处理：**

| Action | 说明 |
|---|---|
| `ACTION_VIEW` | 打开已有笔记（支持从搜索结果跳转） |
| `ACTION_INSERT_OR_EDIT` | 新建笔记（支持通话记录笔记） |

**核心功能：**
- **背景色选择** —— 5 种颜色（黄/蓝/白/绿/红），通过弹出面板选择
- **字体大小选择** —— 4 档（小/中/大/超大）
- **清单模式** —— 切换为 CheckBox + EditText 列表模式，支持标记完成（删除线）
- **闹钟提醒** —— 通过 `DateTimePickerDialog` 设置提醒，使用 `AlarmManager`
- **分享** —— 通过 `Intent.ACTION_SEND` 分享笔记内容
- **发送到桌面** —— 创建桌面快捷方式
- **搜索结果高亮** —— 通过 `BackgroundColorSpan` 高亮匹配文本

**清单模式实现：**
- `switchToListMode()` —— 将文本按 `\n` 分割为多个 `NoteEditText` 条目
- `getWorkingText()` —— 收集每个条目的勾选状态，格式化为 `✓ item` / `□ item`

**生命周期管理：**
- `onPause()` 自动保存笔记
- `onSaveInstanceState()` 确保新笔记在 Activity 被杀死前获得 ID

### 6.3 NoteEditText.java —— 自定义编辑文本

[NoteEditText.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/ui/NoteEditText.java)

继承 `EditText`，专为清单模式设计，支持：

- `KEYCODE_ENTER` → 触发 `onEditTextEnter()` 回调（新建列表项）
- `KEYCODE_DEL`（光标在行首）→ 触发 `onEditTextDelete()` 回调（合并到上一行）
- `onFocusChanged()` → 触发 `onTextChange()` 显示/隐藏 CheckBox
- 支持识别 `tel:`、`http:`、`mailto:` 等 URL Scheme 的上下文菜单

### 6.4 NoteItemData.java —— 列表项数据封装

[NoteItemData.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/ui/NoteItemData.java)

从 Cursor 中解析笔记/文件夹数据，提供位置上下文（是否为首项/末项/单项/跟随文件夹）。

通话记录笔记自动查询联系人姓名。

### 6.5 闹钟系统

| 文件 | 说明 |
|---|---|
| `AlarmReceiver.java` | 接收闹钟广播，弹出提醒通知 |
| `AlarmAlertActivity.java` | 闹钟弹出界面 |
| `AlarmInitReceiver.java` | 开机广播接收器，重新注册所有闹钟 |
| `DateTimePicker.java` | 自定义日期时间选择控件 |
| `DateTimePickerDialog.java` | 日期时间选择对话框 |

### 6.6 其他 UI 组件

| 文件 | 说明 |
|---|---|
| `NotesListAdapter.java` | 笔记列表适配器，支持多选模式 |
| `NotesListItem.java` | 列表项自定义 View |
| `FoldersListAdapter.java` | 文件夹选择列表适配器 |
| `DropdownMenu.java` | 下拉菜单封装 |
| `NotesPreferenceActivity.java` | 设置界面（同步账户、背景色等配置） |

---

## 7. 工具层详解 (net.micode.notes.tool)

### 7.1 DataUtils.java —— 数据操作工具

[DataUtils.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/tool/DataUtils.java)

提供批量数据库操作：

| 方法 | 说明 |
|---|---|
| `batchDeleteNotes(resolver, ids)` | 使用 `ContentProviderOperation` 批量删除笔记 |
| `moveNoteToFoler(resolver, id, src, des)` | 移动单条笔记 |
| `batchMoveToFolder(resolver, ids, folderId)` | 批量移动笔记到文件夹 |
| `getUserFolderCount(resolver)` | 获取用户创建的文件夹数量（排除系统文件夹） |
| `visibleInNoteDatabase(resolver, noteId, type)` | 检查笔记是否可见（不在回收站） |
| `checkVisibleFolderName(resolver, name)` | 检查文件夹名是否重复 |
| `getFolderNoteWidget(resolver, folderId)` | 获取文件夹下所有笔记的小组件属性 |
| `getCallNumberByNoteId(resolver, noteId)` | 根据笔记 ID 获取通话号码 |
| `getNoteIdByPhoneNumberAndCallDate(...)` | 根据号码和日期查找通话笔记 ID |
| `getSnippetById(...)` | 获取笔记摘要 |
| `getFormattedSnippet(...)` | 格式化摘要（取第一行） |

### 7.2 BackupUtils.java —— 备份导出

[BackupUtils.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/tool/BackupUtils.java)

单例模式，将全部笔记导出为文本文件到 SD 卡。

**状态码：**

| 状态 | 值 | 说明 |
|---|---|---|
| `STATE_SD_CARD_UNMOUONTED` | 0 | SD 卡未挂载 |
| `STATE_BACKUP_FILE_NOT_EXIST` | 1 | 备份文件不存在 |
| `STATE_DATA_DESTROIED` | 2 | 数据被破坏 |
| `STATE_SYSTEM_ERROR` | 3 | 系统错误 |
| `STATE_SUCCESS` | 4 | 成功 |

**内部类 TextExport：**
- `exportToText()` —— 遍历所有文件夹和笔记，按格式导出
- `exportFolderToText(folderId, ps)` —— 导出文件夹下的笔记
- `exportNoteToText(noteId, ps)` —— 导出单条笔记内容（区分通话记录和文本笔记）
- 导出文件路径：`{SD卡根目录}/{file_path}/{日期格式文件名}.txt`

### 7.3 ResourceParser.java —— 资源解析器

[ResourceParser.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/tool/ResourceParser.java)

集中管理所有通过索引 ID 映射的资源引用。

**颜色常量：** `YELLOW=0` `BLUE=1` `WHITE=2` `GREEN=3` `RED=4`

**内部类：**

| 类 | 功能 |
|---|---|
| `NoteBgResources` | 编辑界面背景（普通/标题）资源映射 |
| `NoteItemBgResources` | 列表项背景（首/中/尾/单）资源映射 |
| `WidgetBgResources` | 小组件背景（2x/4x）资源映射 |
| `TextAppearanceResources` | 字体大小（小/中/大/超大）样式映射 |

### 7.4 GTaskStringUtils.java —— GTask JSON 常量

[GTaskStringUtils.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/tool/GTaskStringUtils.java)

定义与 Google Tasks API 通信时使用的 JSON 键名和特殊字符串常量，共 40+ 个常量。

---

## 8. GTask 同步层详解 (net.micode.notes.gtask)

### 8.1 同步架构

```
┌──────────────────────┐
│   GTaskSyncService   │  Android Service 入口
│   (startSync/cancel) │
└─────────┬────────────┘
          │
┌─────────▼────────────┐      ┌──────────────────────┐
│   GTaskASyncTask     │─────►│  NotificationManager  │
│   (AsyncTask)        │      │  (通知栏进度)          │
└─────────┬────────────┘      └──────────────────────┘
          │
┌─────────▼────────────┐
│    GTaskManager      │  同步编排器（核心）
│    (单例)             │
└─────────┬────────────┘
          │
┌─────────▼────────────┐      ┌──────────────────────┐
│    GTaskClient        │─────►│  Google Tasks API     │
│    (HTTP 客户端)       │      │  https://mail.google  │
└──────────────────────┘      │  .com/tasks/          │
                              └──────────────────────┘
```

### 8.2 GTaskSyncService.java —— 同步服务

[GTaskSyncService.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/gtask/remote/GTaskSyncService.java)

继承 `Service`，作为同步操作的宿主容器。

**静态方法：**
- `startSync(activity)` —— 启动同步
- `cancelSync(context)` —— 取消同步
- `isSyncing()` —— 查询是否正在同步
- `getProgressString()` —— 获取当前进度文本

### 8.3 GTaskASyncTask.java —— 异步任务

[GTaskASyncTask.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/gtask/remote/GTaskASyncTask.java)

继承 `AsyncTask<Void, String, Integer>`，在 `doInBackground()` 中调用 `GTaskManager.sync()`。通过 `publishProgress()` 更新通知栏进度。

### 8.4 GTaskManager.java —— 同步编排器

[GTaskManager.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/gtask/remote/GTaskManager.java)

单例，是整个同步流程的中央控制器。

**同步流程 (`sync()` 方法)：**

1. **登录 Google 账户** —— 通过 `AccountManager` 获取 authToken，再通过 `GTaskClient.login()` 获取 GTask 会话 Cookie
2. **初始化 GTask 列表 (`initGTaskList()`)** —— 从服务端拉取所有 TaskList 和 Task，建立内存映射
3. **同步内容 (`syncContent()`)**：
   - 处理本地已删除的笔记（`SYNC_ACTION_DEL_REMOTE`）
   - 同步文件夹（先处理 Root 和 Call Record 系统文件夹，再处理用户文件夹）
   - 同步笔记（遍历数据库中的笔记，根据状态决定同步策略）
   - 处理云端新增内容（`SYNC_ACTION_ADD_LOCAL`）
4. **清理** —— 批量删除本地标记删除的条目，提交更新，刷新同步 ID

**同步操作类型（由 Node 定义）：**

| 操作 | 常量 | 说明 |
|---|---|---|
| 无操作 | `SYNC_ACTION_NONE` | 两端无变化 |
| 添加到远端 | `SYNC_ACTION_ADD_REMOTE` | 本地新增，推送至云端 |
| 添加到本地 | `SYNC_ACTION_ADD_LOCAL` | 云端新增，拉取到本地 |
| 删除远端 | `SYNC_ACTION_DEL_REMOTE` | 本地已删，同步删除云端 |
| 删除本地 | `SYNC_ACTION_DEL_LOCAL` | 云端已删，同步删除本地 |
| 更新远端 | `SYNC_ACTION_UPDATE_REMOTE` | 本地修改推送至云端 |
| 更新本地 | `SYNC_ACTION_UPDATE_LOCAL` | 云端修改拉取到本地 |
| 冲突 | `SYNC_ACTION_UPDATE_CONFLICT` | 两端同时修改，以本地为准 |

**同步冲突判定逻辑（Task.getSyncAction()）：**
- 本地无修改 + sync_id 相等 → `NONE`
- 本地无修改 + sync_id 不等 → `UPDATE_LOCAL`（云端更新）
- 本地有修改 + gtask_id 不匹配 → `ERROR`
- 本地有修改 + sync_id 相等 → `UPDATE_REMOTE`（推送本地）
- 本地有修改 + sync_id 不等 → `UPDATE_CONFLICT`（冲突，本地优先）

### 8.5 GTaskClient.java —— HTTP 客户端

[GTaskClient.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/gtask/remote/GTaskClient.java)

单例，封装与 Google Tasks API 的所有 HTTP 通信。

**认证流程：**
1. 通过 `AccountManager.getAuthToken()` 获取 Google 账户的 authToken
2. 使用 authToken 访问 `https://mail.google.com/tasks/ig` 获取 GTask 专用 Cookie
3. 从响应中解析 `_setup()` JavaScript 片段获取 `client_version`
4. Cookie 有效期为 5 分钟，超时自动重新登录

**API 操作：**

| 方法 | HTTP 方式 | 说明 |
|---|---|---|
| `getTaskLists()` | GET | 获取所有 TaskList |
| `getTaskList(gid)` | POST（action=get_all） | 获取指定 TaskList 下的 Tasks |
| `createTask(task)` | POST（action=create） | 创建 Task |
| `createTaskList(tasklist)` | POST（action=create） | 创建 TaskList |
| `addUpdateNode(node)` | 缓存 | 添加更新节点到批量更新队列（最多缓存 10 个） |
| `commitUpdate()` | POST（批量 update） | 提交所有缓存的更新操作 |
| `moveTask(task, pre, cur)` | POST（action=move） | 移动 Task |
| `deleteNode(node)` | POST（action=update, deleted=true） | 标记删除节点 |

### 8.6 GTask 数据模型 (net.micode.notes.gtask.data)

**继承层次：**

```
Node (abstract)
├── Task（Google Task — 一条笔记）
│   └── MetaData（元数据 — 关联 GTask 与本地 SQL 数据）
└── TaskList（Google Task List — 一个文件夹）
```

**Node.java** —— 抽象基类，定义同步操作常量和基本属性（gid、name、lastModified、deleted）。

**Task.java** —— 表示 Google Tasks 中的一条 Task（对应一条笔记），包含：
- `mCompleted` —— 是否完成
- `mNotes` —— 笔记详细内容
- `mMetaInfo` —— 关联的元数据 JSON
- `mPriorSibling` / `mParent` —— 任务在列表中的位置

**TaskList.java** —— 表示 Google Tasks 中的 Task List（对应一个文件夹），管理子 Task 的增删改移。

**MetaData.java** —— 继承 Task，用于在 GTask 中存储元数据，关联 GTask ID 与本地 SQL 数据。通过 `setMeta(gid, json)` 将本地笔记的完整 JSON 数据序列化到 GTask 的 Notes 字段中。

**SqlNote.java** —— 本地 SQL 数据与 JSON 的转换桥接：
- `setContent(JSONObject)` —— 从 JSON 解析并设置所有 note/data 字段
- `getContent()` —— 将当前状态序列化为 JSON
- `commit(validateVersion)` —— 写入数据库，支持乐观锁版本校验

**SqlData.java** —— 同上，处理 data 表级别的转换。

### 8.7 异常定义

| 异常 | 类型 | 说明 |
|---|---|---|
| `ActionFailureException` | RuntimeException | 同步过程中的逻辑错误 |
| `NetworkFailureException` | Exception | 网络通信失败 |

---

## 9. 桌面小组件层 (net.micode.notes.widget)

### 9.1 NoteWidgetProvider.java —— 小组件基类

[NoteWidgetProvider.java](file:///d:/CODE/Notesmaster/app/src/main/java/net/micode/notes/widget/NoteWidgetProvider.java)

继承 `AppWidgetProvider`，提供 2x2 和 4x4 两种尺寸的小组件。核心功能：
- **onUpdate()** —— 从数据库查询关联笔记的内容和背景色，渲染 `RemoteViews`
- **onDeleted()** —— 小组件被移除时，清除数据库中笔记的 widget_id 关联
- **点击跳转** —— 通过 `PendingIntent` 跳转到 `NoteEditActivity`

### 9.2 具体实现

| 类 | 小组件尺寸 |
|---|---|
| `NoteWidgetProvider_2x` | 2x2 |
| `NoteWidgetProvider_4x` | 4x4 |

---

## 10. AndroidManifest 关键声明

[AndroidManifest.xml](file:///d:/CODE/Notesmaster/app/src/main/AndroidManifest.xml)

**权限：**
- `WRITE_EXTERNAL_STORAGE` —— SD 卡备份导出
- `INTERNET` —— Google Tasks 同步
- `READ_CONTACTS` —— 通话记录联系人查询
- `MANAGE_ACCOUNTS / AUTHENTICATE_ACCOUNTS / GET_ACCOUNTS / USE_CREDENTIALS` —— Google 账户认证
- `RECEIVE_BOOT_COMPLETED` —— 开机重新注册闹钟
- `INSTALL_SHORTCUT` —— 桌面快捷方式

**四大组件：**

| 组件 | 类型 | 说明 |
|---|---|---|
| `NotesListActivity` | Activity (LAUNCHER) | 主界面 |
| `NoteEditActivity` | Activity | 编辑界面，处理 VIEW/INSERT_OR_EDIT/SEARCH |
| `NotesPreferenceActivity` | Activity | 设置界面 |
| `AlarmAlertActivity` | Activity | 闹钟弹出界面 |
| `NotesProvider` | ContentProvider (authorities="micode_notes") | 数据提供者 |
| `NoteWidgetProvider_2x` | BroadcastReceiver | 2x2 小组件 |
| `NoteWidgetProvider_4x` | BroadcastReceiver | 4x4 小组件 |
| `AlarmReceiver` | BroadcastReceiver (process=":remote") | 闹钟接收器 |
| `AlarmInitReceiver` | BroadcastReceiver | 开机初始化闹钟 |
| `GTaskSyncService` | Service | 后台同步服务 |

---

## 11. 项目运行方式

### 11.1 环境要求

- **Android Studio** (Ladybug 及以上，支持 AGP 9.0.0)
- **JDK 17+**（Gradle 9.x 要求）
- **Android SDK** (API 36, min API 24)
- **Google Play Services**（用于 Google 账户认证）

### 11.2 构建步骤

```bash
# 1. 克隆/进入项目目录
cd d:\CODE\Notesmaster

# 2. 使用 Gradle Wrapper 构建
gradlew assembleDebug

# 3. 生成的 APK 位于
# app/build/outputs/apk/debug/app-debug.apk
```

### 11.3 在 Android Studio 中运行

1. 使用 Android Studio 打开 `d:\CODE\Notesmaster` 目录
2. 等待 Gradle 同步完成
3. 连接 Android 设备或启动模拟器（API ≥ 24）
4. 点击 Run → Run 'app'

### 11.4 注意事项

- **Google Tasks 同步**需要设备已登录 Google 账户，并在设置中配置同步账户名
- **SD 卡备份导出**需要设备具有外部存储权限
- **通话记录笔记**需要设备具有读取联系人和通话记录的权限
- `httpcomponents-client` 库需要通过本地路径引用（在 [build.gradle.kts](file:///d:/CODE/Notesmaster/app/build.gradle.kts) 中使用 `files()` 引用绝对路径），若在其他环境构建需调整路径

---

## 12. 完整文件清单

```
app/src/main/java/net/micode/notes/
├── data/
│   ├── Contact.java                  — 通讯录查询
│   ├── Notes.java                    — 数据契约（URI、列名、常量）
│   ├── NotesDatabaseHelper.java      — SQLite 数据库管理
│   └── NotesProvider.java            — ContentProvider
├── model/
│   ├── Note.java                     — 数据持久化模型
│   └── WorkingNote.java              — 运行时工作模型
├── tool/
│   ├── BackupUtils.java              — SD 卡备份导出
│   ├── DataUtils.java                — 数据操作工具
│   ├── GTaskStringUtils.java         — GTask JSON 键名常量
│   └── ResourceParser.java           — 颜色/字体/背景资源映射
├── ui/
│   ├── AlarmAlertActivity.java       — 闹钟弹出 Activity
│   ├── AlarmInitReceiver.java        — 开机闹钟初始化
│   ├── AlarmReceiver.java            — 闹钟广播接收器
│   ├── DateTimePicker.java           — 日期时间选择器
│   ├── DateTimePickerDialog.java     — 日期时间对话框
│   ├── DropdownMenu.java             — 下拉菜单
│   ├── FoldersListAdapter.java       — 文件夹列表适配器
│   ├── NoteEditActivity.java         — 笔记编辑 Activity
│   ├── NoteEditText.java             — 清单模式自定义 EditText
│   ├── NoteItemData.java             — 列表项数据封装
│   ├── NotesListActivity.java        — 主列表 Activity
│   ├── NotesListAdapter.java         — 笔记列表适配器
│   ├── NotesListItem.java            — 笔记列表项 View
│   └── NotesPreferenceActivity.java  — 设置 Activity
├── widget/
│   ├── NoteWidgetProvider.java       — 小组件基类
│   ├── NoteWidgetProvider_2x.java    — 2x2 小组件
│   └── NoteWidgetProvider_4x.java    — 4x4 小组件
└── gtask/
    ├── data/
    │   ├── MetaData.java             — 元数据 Task
    │   ├── Node.java                 — 同步节点抽象基类
    │   ├── SqlData.java              — Data 表 ↔ JSON
    │   ├── SqlNote.java              — Note 表 ↔ JSON
    │   ├── Task.java                 — GTask Task
    │   └── TaskList.java             — GTask TaskList
    ├── exception/
    │   ├── ActionFailureException.java
    │   └── NetworkFailureException.java
    └── remote/
        ├── GTaskASyncTask.java       — 同步 AsyncTask
        ├── GTaskClient.java          — GTask HTTP 客户端
        ├── GTaskManager.java         — 同步编排器
        └── GTaskSyncService.java     — 同步 Service
```

---

## 13. 关键数据流

### 13.1 创建笔记流程

```
User clicks "New" → NotesListActivity.createNewNote()
  → Intent(ACTION_INSERT_OR_EDIT) → NoteEditActivity
    → WorkingNote.createEmptyNote() → Note() 初始化
    → User edits → setWorkingText() → Note.setTextData()
    → onPause() → saveNote() → Note.syncNote()
      → ContentResolver.update/insert → NotesProvider
        → SQLiteDatabase → triggers update snippet
```

### 13.2 Google Tasks 同步流程

```
User clicks "Sync" → NotesListActivity.onOptionsItemSelected()
  → GTaskSyncService.startSync()
    → GTaskSyncService.onStartCommand()
      → GTaskASyncTask.execute()
        → GTaskManager.sync()
          → GTaskClient.login() → AccountManager + HTTP
          → initGTaskList() → 拉取 TaskLists 和 Tasks
          → syncContent()
            → syncFolder() → 同步 Root/CallRecord/用户文件夹
            → 遍历本地笔记 → getSyncAction() 决定操作
            → doContentSync() → addLocal/addRemote/del.../update...
          → commitUpdate() → 提交批量更新
          → refreshLocalSyncId() → 更新本地 sync_id
```

### 13.3 备份导出流程

```
User clicks "Export" → NotesListActivity.exportNoteToText()
  → BackupUtils.exportToText()
    → TextExport.exportToText()
      → 检测 SD 卡挂载状态
      → 创建导出文件
      → 遍历文件夹 Cursor → exportFolderToText()
        → 遍历笔记 Cursor → exportNoteToText()
          → 区分 CALL_NOTE / NOTE 类型
          → 按格式输出到 PrintStream
```

---

