# Lesson 5: Skills

`L00 > L01 > L02 > L03 > L04 > [ L05 ] L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"Don't put everything in the system prompt. Load on demand."* -- two-layer injection keeps context lean until the model actually needs specialized knowledge.

## Problem

An agent that can commit code, create PRs, deploy, run tests, and refactor needs different instructions for each task. Stuffing all instructions into the system prompt wastes tokens on every request -- even when the agent is just reading a file.

The naive approach (everything in system prompt) costs ~500-2000 tokens per skill, multiplied by every single API call. With 10 skills, that is 5,000-20,000 tokens of overhead on every request, most of it irrelevant.

## Solution

```
Layer 1 (system prompt): short descriptions only (~100 tokens/skill)
+-----------------------------------------------------+
| Skills available:                                    |
|   - commit: Create git commits  [git]                |
|   - review-pr: Review pull requests  [git, review]   |
|   - deploy: Deploy to staging  [ops]                 |
+-----------------------------------------------------+
        |
        | Model sees names and decides
        | which skill it needs
        |
        v
Layer 2 (tool_result): full body loaded on demand
+-----------------------------------------------------+
| <skill name="commit">                                |
| ## How to create a commit                            |
| 1. Stage files with ...                              |
| 2. Write message following ...                       |
| 3. Never amend unless ...                            |
| (full instructions, 500-2000 tokens)                 |
| </skill>                                             |
+-----------------------------------------------------+

         Only loaded when the model calls load_skill("commit").
         Other skills stay unloaded.
```

## How It Works

1. The `SkillLoader` scans `skills/<name>/SKILL.md` files with YAML frontmatter.

```java
static class SkillLoader {
    private final Map<String, Skill> skills = new HashMap<>();

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    private void loadSkill(Path skillFile) {
        String text = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
        Map<String, String> meta = new HashMap<>();
        String body = text;

        // Parse YAML frontmatter between --- delimiters
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

A SKILL.md file looks like:

```markdown
---
name: commit
description: Create git commits following project conventions
tags: git, workflow
---

## How to create a commit

1. Run `git status` to see changes
2. Stage specific files (never use `git add -A`)
3. Write a concise commit message...
```

The frontmatter provides metadata for Layer 1. The body is the full instructions for Layer 2.

2. Layer 1: inject short descriptions into the system prompt.

```java
// Layer 1: skill metadata injected into system prompt
String skillDescriptions = skillLoader.getDescriptions();
String sysPrompt = "You are a coding agent at " + workDir + ".\n" +
        "Use load_skill to access specialized knowledge before tackling "
        + "unfamiliar topics.\n\n" +
        "Skills available:\n" + skillDescriptions;
```

The `getDescriptions()` method produces a compact list:

```java
public String getDescriptions() {
    if (skills.isEmpty()) return "(no skills available)";

    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Skill> entry : skills.entrySet()) {
        String name = entry.getKey();
        Skill skill = entry.getValue();
        String desc = skill.meta.getOrDefault("description", "No description");
        String tags = skill.meta.getOrDefault("tags", "");

        sb.append("  - ").append(name).append(": ").append(desc);
        if (!tags.isEmpty()) {
            sb.append(" [").append(tags).append("]");
        }
        sb.append("\n");
    }
    return sb.toString().trim();
}
```

This adds ~100 tokens per skill to the system prompt. The model sees names and descriptions, enough to decide when to load one.

3. Layer 2: the `load_skill` tool returns the full body in the tool result.

```java
tools.add(ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("load_skill")
                .description("Load specialized knowledge by name.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "name", Map.of("type", "string",
                                        "description", "Skill name to load")
                        )))
                        .putAdditionalProperty("required", JsonValue.from(List.of("name")))
                        .build())
                .build())
        .build());
```

The handler returns the full skill body wrapped in XML tags:

```java
handlers.put("load_skill", args -> skillLoader.getContent((String) args.get("name")));

// In SkillLoader:
public String getContent(String name) {
    Skill skill = skills.get(name);
    if (skill == null) {
        return "Error: Unknown skill '" + name
                + "'. Available: " + String.join(", ", skills.keySet());
    }
    return "<skill name=\"" + name + "\">\n" + skill.body + "\n</skill>";
}
```

When the model calls `load_skill("commit")`, the full commit instructions arrive as a tool result. The model reads them and follows the instructions. Skills that are never loaded cost zero tokens beyond the Layer 1 description.

4. The skill directory structure.

```
skills/
  commit/
    SKILL.md          <-- frontmatter + full instructions
  review-pr/
    SKILL.md
  deploy/
    SKILL.md
```

Each skill is a directory containing a `SKILL.md` file. The `SkillLoader` uses `Files.walk()` to find all `SKILL.md` files recursively. The directory name is the default skill name (overridden by the `name` field in frontmatter).

## What Changed

| Component     | Lesson 4                | Lesson 5                          |
|---------------|-------------------------|-----------------------------------|
| System prompt | Static text             | Dynamic: base + skill descriptions |
| Knowledge     | Hardcoded in prompt     | External SKILL.md files           |
| Token cost    | All knowledge always loaded | Layer 1: ~100 tok/skill, Layer 2: on demand |
| Tools         | base + task             | base + `load_skill`               |
| Extensibility | Edit Java code          | Drop a SKILL.md file              |
| Loop          | Same                    | Same                              |

## Try It

First, create a skill:

```sh
mkdir -p skills/commit
cat > skills/commit/SKILL.md << 'EOF'
---
name: commit
description: Create git commits following project conventions
tags: git, workflow
---

## How to create a commit

1. Run `git status` to see staged and unstaged changes.
2. Stage specific files by name -- never use `git add -A`.
3. Write a concise commit message (1-2 sentences).
4. Never amend existing commits unless explicitly asked.
EOF
```

Then run:

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson5 --prompt='I need to commit my changes. Load the relevant skill first.'"
```

**Source**: [`Lesson5RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson5RunSimple.java)
