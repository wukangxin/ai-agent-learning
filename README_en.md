# AI Agent Learning

[中文版](README.md)

A progressive, hands-on tutorial for building AI agents with the OpenAI Java SDK. Through 14 incremental lessons (Lesson 0–13), you'll learn to construct a full-featured autonomous agent from scratch — starting with a basic chat loop and ending with a multi-agent system capable of parallel task execution.

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Lessons](#lessons)
- [Web Tutorial Site](#web-tutorial-site)
- [Testing](#testing)
- [License](#license)

## Features

- 14 progressive lessons covering the full spectrum of agent design patterns
- Pure Java implementation with no heavyweight agent framework dependencies
- File-based task management, message passing, and skill loading
- Multi-agent collaboration with protocols (shutdown, plan approval)
- Background task execution and context compression
- 213 unit tests covering core components
- Interactive web tutorial site with bilingual support (English / Chinese)

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 17 |
| Spring Boot | 3.4.3 |
| OpenAI Java SDK | 0.31.0 |
| JUnit | 5 |
| Maven | 3.8+ |
| Next.js (Web) | 16.1 |
| React (Web) | 19.2 |
| Tailwind CSS (Web) | 4 |

## Prerequisites

- JDK 17 or later
- Maven 3.8+
- An OpenAI API key
- Node.js 18+ (for the web tutorial site)

## Getting Started

1. **Clone the repository**

   ```bash
   git clone https://github.com/wukangxin/ai-agent-learning.git
   cd ai-agent-learning
   ```

2. **Set your API key**

   Create a file `env.local` in the project root:

   ```
   OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
   ```

3. **Build the project**

   ```bash
   mvn clean compile
   ```

4. **Run a lesson**

   Each lesson is a standalone class with a `main` method:

   ```bash
   cd openai
   mvn exec:java -Dexec.mainClass="ai.agent.learning.lesson.Lesson0RunSimple"
   ```

5. **Run tests**

   ```bash
   mvn test
   ```

## Project Structure

```
ai-agent-learning/
├── pom.xml                          # Parent POM
├── openai/                          # OpenAI module
│   ├── pom.xml
│   └── src/
│       ├── main/java/ai/agent/learning/
│       │   ├── app/                 # Spring Boot application entry
│       │   ├── base/                # Base RunSimple interface
│       │   └── lesson/              # Lesson 0–13 source files
│       └── test/java/ai/agent/learning/
│           └── lesson/              # Unit tests
├── docs/                            # Bilingual documentation
│   └── openai/
│       ├── en/                      # English lesson docs
│       └── zh/                      # Chinese lesson docs
└── web/                             # Interactive tutorial website
    ├── src/
    │   ├── app/                     # Next.js App Router pages
    │   ├── components/              # React components
    │   ├── lib/                     # Utilities and constants
    │   └── i18n/                    # Internationalization (en/zh)
    └── scripts/                     # Build-time content extraction
```

## Lessons

| # | Topic | Description |
|---|-------|-------------|
| 0 | Basic Chat Loop | Minimal chat completion loop — the foundation of every agent |
| 1 | Tool Use | Introduce function calling and tool dispatch |
| 2 | Tools | Add multiple tools with a dispatch map; the loop stays the same |
| 3 | TodoWrite | Agent tracks its own progress via a TodoManager with nag reminders |
| 4 | Subagents | Spawn child agents with isolated context, sharing only the filesystem |
| 5 | Skills | Two-layer skill injection: cheap names in system prompt, full body on demand |
| 6 | Compact | Three-layer context compression so the agent can work indefinitely |
| 7 | Tasks | File-based task management with dependency tracking |
| 8 | Background Tasks | Run commands in background threads with a notification queue |
| 9 | Agent Teams | Persistent named agents with file-based JSONL inboxes and message bus |
| 10 | Team Protocols | Shutdown and plan approval protocols using request-id correlation |
| 11 | Autonomous Agents | Idle-cycle task board polling with auto-claiming and identity re-injection |
| 12 | Worktree Isolation | Directory-level isolation for parallel task execution |
| 13 | Full Reference Agent | Capstone — combines every mechanism from Lessons 1–12 |

### Architectural Layers

The 14 lessons are organized into five orthogonal layers:

| Layer | Lessons | Description |
|-------|---------|-------------|
| Tools & Execution | L00, L01, L02 | The foundation — tools give the model capabilities to interact with the world |
| Planning & Coordination | L03, L04, L05, L07 | From simple todo lists to dependency-aware task boards |
| Memory Management | L06 | Compression strategies that let agents work infinitely |
| Concurrency | L08 | Background threads and notification buses for parallel work |
| Collaboration | L09–L13 | Multi-agent coordination, protocols, and autonomous teammates |

## Web Tutorial Site

The project includes an interactive web tutorial built with Next.js, featuring:

- **Bilingual interface** — switch between English and Chinese
- **Learning path timeline** — visual progress through all 14 lessons
- **Lesson pages** — documentation + syntax-highlighted Java source code
- **Layer view** — lessons organized by architectural concern
- **Compare tool** — side-by-side diff between any two lessons
- **Dark mode** — automatic and manual theme switching
- **Responsive design** — works on desktop and mobile

### Run the web site locally

```bash
cd web
npm install
npm run dev
```

Then open http://localhost:3000/ai-agent-learning in your browser.

## Testing

The project includes 213 unit tests covering core inner-class components:

```bash
mvn test
```

| Test Class | Covers |
|------------|--------|
| `Lesson2ToolHandlerTest` | Tool dispatch logic |
| `Lesson3TodoManagerTest` | Todo CRUD operations |
| `Lesson5SkillLoaderTest` | Skill loading and injection |
| `Lesson7TaskManagerTest` | Task CRUD and dependency graph |
| `Lesson8BackgroundManagerTest` | Background task execution and notifications |
| `Lesson9JsonHelpersTest` | JSON parsing and serialization helpers |
| `Lesson9MessageBusTest` | Message bus send, read, and broadcast |
| `Lesson10ProtocolTest` | Protocol message bus and teammate management |
| `Lesson11AutonomousTest` | Task scanning, claiming, and identity block |
| `Lesson12EventBusTest` | Event publish-subscribe system |
| `Lesson12TaskManagerTest` | Worktree task manager with file persistence |
| `Lesson13FullAgentTest` | Full agent — TodoManager, SkillLoader, TaskManager, BackgroundManager, MessageBus |

## License

This project is licensed under the [MIT License](LICENSE).
