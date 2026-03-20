# AI Agent Learning

A progressive, hands-on tutorial for building AI agents with the OpenAI Java SDK. Through 14 incremental lessons (Lesson 0–13), you'll learn to construct a full-featured autonomous agent from scratch — starting with a basic chat loop and ending with a multi-agent system capable of parallel task execution.

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Lessons](#lessons)
- [Testing](#testing)
- [License](#license)

## Features

- 14 progressive lessons covering the full spectrum of agent design patterns
- Pure Java implementation with no heavyweight agent framework dependencies
- File-based task management, message passing, and skill loading
- Multi-agent collaboration with protocols (shutdown, plan approval)
- Background task execution and context compression
- 213 unit tests covering core components

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 17 |
| Spring Boot | 3.4.3 |
| OpenAI Java SDK | 0.31.0 |
| JUnit | 5 |
| Maven | 3.8+ |

## Prerequisites

- JDK 17 or later
- Maven 3.8+
- An OpenAI API key

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

This project is for educational purposes.
