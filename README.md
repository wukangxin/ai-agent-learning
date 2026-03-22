# AI Agent 学习教程

[English](README_en.md)

一个渐进式、实践导向的 AI Agent 构建教程，基于 OpenAI Java SDK。通过 14 节递增式课程（第 0–13 课），你将学习从零构建一个功能完备的自主 Agent —— 从基础聊天循环开始，直到多 Agent 协作系统，实现并行任务执行。

## 目录

- [特性](#特性)
- [技术栈](#技术栈)
- [前置要求](#前置要求)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [课程列表](#课程列表)
- [在线教学网站](#在线教学网站)
- [测试](#测试)
- [许可证](#许可证)

## 特性

- 14 节渐进式课程，覆盖 Agent 设计模式的完整光谱
- 纯 Java 实现，无重量级 Agent 框架依赖
- 基于文件的任务管理、消息传递和技能加载
- 多 Agent 协作，支持协议（关机、计划审批）
- 后台任务执行和上下文压缩
- 213 个单元测试覆盖核心组件
- 交互式教学网站，支持中英双语

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.4.3 |
| OpenAI Java SDK | 0.31.0 |
| JUnit | 5 |
| Maven | 3.8+ |
| Next.js（Web） | 16.1 |
| React（Web） | 19.2 |
| Tailwind CSS（Web） | 4 |

## 前置要求

- JDK 17 或更高版本
- Maven 3.8+
- OpenAI API 密钥
- Node.js 18+（用于教学网站）

## 快速开始

1. **克隆仓库**

   ```bash
   git clone https://github.com/wukangxin/ai-agent-learning.git
   cd ai-agent-learning
   ```

2. **设置 API 密钥**

   在项目根目录创建 `env.local` 文件：

   ```
   OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
   ```

3. **构建项目**

   ```bash
   mvn clean compile
   ```

4. **运行课程**

   每节课都是一个独立的类，包含 `main` 方法：

   ```bash
   cd openai
   mvn exec:java -Dexec.mainClass="ai.agent.learning.lesson.Lesson0RunSimple"
   ```

5. **运行测试**

   ```bash
   mvn test
   ```

## 项目结构

```
ai-agent-learning/
├── pom.xml                          # 父 POM
├── openai/                          # OpenAI 模块
│   ├── pom.xml
│   └── src/
│       ├── main/java/ai/agent/learning/
│       │   ├── app/                 # Spring Boot 应用入口
│       │   ├── base/                # RunSimple 基础接口
│       │   └── lesson/              # 第 0–13 课源码
│       └── test/java/ai/agent/learning/
│           └── lesson/              # 单元测试
├── docs/                            # 双语文档
│   └── openai/
│       ├── en/                      # 英文课程文档
│       └── zh/                      # 中文课程文档
└── web/                             # 交互式教学网站
    ├── src/
    │   ├── app/                     # Next.js App Router 页面
    │   ├── components/              # React 组件
    │   ├── lib/                     # 工具函数和常量
    │   └── i18n/                    # 国际化（中/英）
    └── scripts/                     # 构建时内容提取脚本
```

## 课程列表

| # | 主题 | 描述 |
|---|------|------|
| 0 | 基础聊天循环 | 最小化聊天完成循环 —— 每个 Agent 的基石 |
| 1 | 工具使用 | 引入函数调用和工具分发 |
| 2 | 多工具 | 使用分发映射添加多个工具；循环保持不变 |
| 3 | 待办管理 | Agent 通过 TodoManager 跟踪自身进度，带提醒机制 |
| 4 | 子 Agent | 生成具有隔离上下文的子 Agent，仅共享文件系统 |
| 5 | 技能系统 | 两层技能注入：系统提示中放名称，按需加载完整内容 |
| 6 | 上下文压缩 | 三层上下文压缩，让 Agent 可以无限工作 |
| 7 | 任务系统 | 基于文件的任务管理，支持依赖追踪 |
| 8 | 后台任务 | 在后台线程中运行命令，配合通知队列 |
| 9 | Agent 团队 | 持久化命名 Agent，基于文件的 JSONL 收件箱和消息总线 |
| 10 | 团队协议 | 使用 request-id 关联的关机和计划审批协议 |
| 11 | 自主 Agent | 空闲周期任务板轮询，自动认领和身份重注入 |
| 12 | 工作树隔离 | 目录级别隔离，实现并行任务执行 |
| 13 | 完整参考 Agent | 毕业项目 —— 整合第 1–12 课的所有机制 |

### 架构层次

14 节课程按五个正交层次组织：

| 层次 | 课程 | 描述 |
|------|------|------|
| 工具与执行 | L00, L01, L02 | 基础层 —— 工具赋予模型与外部世界交互的能力 |
| 规划与协调 | L03, L04, L05, L07 | 从简单的待办列表到依赖感知的任务板 |
| 内存管理 | L06 | 压缩策略让 Agent 可以无限工作 |
| 并发 | L08 | 后台线程和通知总线实现并行工作 |
| 协作 | L09–L13 | 多 Agent 协调、协议和自主队友 |

## 在线教学网站

项目包含一个基于 Next.js 构建的交互式教学网站，特性包括：

- **双语界面** —— 中英文自由切换
- **学习路径时间线** —— 14 节课程的可视化进度
- **课程页面** —— 文档 + 语法高亮的 Java 源码
- **架构层视图** —— 按架构关注点组织课程
- **对比工具** —— 任意两节课的代码差异对比
- **暗色模式** —— 自动和手动主题切换
- **响应式设计** —— 桌面端和移动端自适应

### 本地运行教学网站

```bash
cd web
npm install
npm run dev
```

然后在浏览器中打开 http://localhost:3000/ai-agent-learning。

## 测试

项目包含 213 个单元测试，覆盖核心内部类组件：

```bash
mvn test
```

| 测试类 | 覆盖范围 |
|--------|----------|
| `Lesson2ToolHandlerTest` | 工具分发逻辑 |
| `Lesson3TodoManagerTest` | 待办 CRUD 操作 |
| `Lesson5SkillLoaderTest` | 技能加载和注入 |
| `Lesson7TaskManagerTest` | 任务 CRUD 和依赖图 |
| `Lesson8BackgroundManagerTest` | 后台任务执行和通知 |
| `Lesson9JsonHelpersTest` | JSON 解析和序列化辅助 |
| `Lesson9MessageBusTest` | 消息总线发送、读取和广播 |
| `Lesson10ProtocolTest` | 协议消息总线和队友管理 |
| `Lesson11AutonomousTest` | 任务扫描、认领和身份块 |
| `Lesson12EventBusTest` | 事件发布-订阅系统 |
| `Lesson12TaskManagerTest` | 工作树任务管理器与文件持久化 |
| `Lesson13FullAgentTest` | 完整 Agent —— TodoManager、SkillLoader、TaskManager、BackgroundManager、MessageBus |

## 致谢

本项目灵感来源于 [learn-claude-code](https://github.com/anthropics/learn-claude-code) 项目（Python 版本）。

## 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。
