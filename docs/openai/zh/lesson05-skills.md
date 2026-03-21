# Lesson 5: Skills (技能加载)

`L00 > L01 > L02 > L03 > L04 > [ L05 ] L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"别把所有东西塞进 system prompt。按需加载。"* -- 两层注入, 用最少的 token 覆盖最多的知识。

## 问题

智能体需要领域知识 -- 编码规范、框架约定、部署流程等。天真的做法是把所有知识塞进 system prompt, 但这有严重问题:

- **token 浪费**: 每次 API 调用都发送全部知识, 即使 90% 用不到
- **注意力稀释**: system prompt 越长, 模型对每条指令的关注度越低
- **不可扩展**: 10 个技能还行, 100 个技能就爆了

我们需要一个**按需加载**的机制。

## 解决方案

```
+----------------------------------------------------+
|                  System Prompt                      |
|                                                     |
|  Skills available:                                  |
|    - git-workflow: Git 分支和 PR 流程 [git, vcs]    |  <-- Layer 1: 仅名称+描述
|    - spring-boot: Spring Boot 约定 [java, spring]   |      (~100 tokens/skill)
|    - testing: 测试策略和工具 [test, junit]           |
+----------------------------------------------------+
                         |
                    模型调用 load_skill("spring-boot")
                         |
                         v
+----------------------------------------------------+
|              Tool Result                            |
|                                                     |
|  <skill name="spring-boot">                        |  <-- Layer 2: 完整内容
|  # Spring Boot Conventions                          |      (仅在需要时加载)
|                                                     |
|  ## Project Structure                               |
|  - src/main/java: 源码                              |
|  - src/main/resources: 配置                         |
|  - application.yml: 统一配置文件                     |
|  ...                                                |
|  </skill>                                           |
+----------------------------------------------------+
```

两层注入策略:
- **Layer 1 (系统提示)**: 所有技能的名称和一句话描述, 成本极低 (~100 tokens/技能)
- **Layer 2 (工具结果)**: 完整技能内容, 仅在模型主动请求时加载

## 工作原理

### 1. SKILL.md 文件格式

```markdown
---
name: spring-boot
description: Spring Boot 项目约定和最佳实践
tags: java, spring, boot
---

# Spring Boot Conventions

## Project Structure
- `src/main/java`: Java 源码
- `src/main/resources/application.yml`: 统一配置

## Naming Conventions
- Controller: `XxxController`
- Service: `XxxService`
- Repository: `XxxRepository`

## Configuration
使用 `@Value("${config.key}")` 注入配置...
```

YAML 前置元数据 (`---` 之间) 包含结构化的元信息; 正文是 Markdown 格式的知识内容。

### 2. SkillLoader: 扫描和解析

```java
static class SkillLoader {
    private final Map<String, Skill> skills = new HashMap<>();

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    private void loadAll(Path skillsDir) {
        if (!Files.exists(skillsDir)) return;

        Files.walk(skillsDir)
                .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                .sorted()
                .forEach(this::loadSkill);
    }

    private void loadSkill(Path skillFile) {
        String text = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
        Map<String, String> meta = new HashMap<>();
        String body = text;

        // 解析 YAML 前置元数据
        Pattern pattern = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            body = matcher.group(2).trim();

            for (String line : frontmatter.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    meta.put(key, value);
                }
            }
        }

        String name = meta.getOrDefault("name",
            skillFile.getParent().getFileName().toString());
        skills.put(name, new Skill(meta, body, skillFile.toString()));
    }
}
```

目录结构:
```
skills/
  git-workflow/
    SKILL.md
  spring-boot/
    SKILL.md
  testing/
    SKILL.md
```

### 3. Layer 1: 系统提示注入

```java
// Layer 1: 短描述注入系统提示
public String getDescriptions() {
    if (skills.isEmpty()) return "(no skills available)";

    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Skill> entry : skills.entrySet()) {
        String name = entry.getKey();
        Skill skill = entry.getValue();
        String desc = skill.meta.getOrDefault("description", "No description");
        String tags = skill.meta.getOrDefault("tags", "");

        sb.append("  - ").append(name).append(": ").append(desc);
        if (!tags.isEmpty()) sb.append(" [").append(tags).append("]");
        sb.append("\n");
    }
    return sb.toString().trim();
}

// 构建系统提示
String skillDescriptions = skillLoader.getDescriptions();
String sysPrompt = "You are a coding agent at " + workDir + ".\n" +
        "Use load_skill to access specialized knowledge before " +
        "tackling unfamiliar topics.\n\n" +
        "Skills available:\n" + skillDescriptions;
```

### 4. Layer 2: load_skill 工具

```java
// 工具定义
tools.add(ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("load_skill")
                .description("Load specialized knowledge by name.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "name", Map.of("type", "string",
                                        "description", "Skill name to load"))))
                        .putAdditionalProperty("required", JsonValue.from(List.of("name")))
                        .build())
                .build())
        .build());

// 处理函数
handlers.put("load_skill", args -> skillLoader.getContent((String) args.get("name")));

// Layer 2: 完整内容返回
public String getContent(String name) {
    Skill skill = skills.get(name);
    if (skill == null) {
        return "Error: Unknown skill '" + name + "'. Available: "
               + String.join(", ", skills.keySet());
    }
    return "<skill name=\"" + name + "\">\n" + skill.body + "\n</skill>";
}
```

### 工作流程

```
1. 用户: "按照项目规范重构 UserService"
2. 模型看到 system prompt 中的技能列表
3. 模型调用 load_skill("spring-boot")  -- 获取 Spring Boot 规范
4. 模型按照规范进行重构
5. 技能内容只在需要时出现在上下文中
```

## 变更内容

| 组件          | 之前 (L04)         | 之后 (L05)                        |
|---------------|--------------------|------------------------------------|
| 知识注入      | 硬编码 system      | 两层注入 (元数据 + 按需加载)      |
| 技能存储      | (无)               | `skills/<name>/SKILL.md`          |
| 元数据解析    | (无)               | YAML 前置元数据                    |
| system prompt | 固定内容           | 动态拼接技能列表                   |
| 新工具        | (无)               | `load_skill`                       |

## 试一试

1. 创建技能目录:

```sh
mkdir -p skills/java-conventions
```

2. 创建 `skills/java-conventions/SKILL.md`:

```markdown
---
name: java-conventions
description: Java 编码规范和最佳实践
tags: java, style, conventions
---

# Java Conventions

## Naming
- 类名: PascalCase (UserService)
- 方法名: camelCase (getUserById)
- 常量: UPPER_SNAKE_CASE (MAX_RETRY_COUNT)

## Error Handling
- 使用自定义异常而非通用 Exception
- 在 Service 层捕获, 在 Controller 层转换为 HTTP 响应
```

3. 运行:

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson5 --prompt='检查项目代码是否符合 Java 编码规范'"
```

观察模型是否主动调用 `load_skill` 来获取规范, 然后据此进行检查。

**源码**: [`Lesson5RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson5RunSimple.java)
