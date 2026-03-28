package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessage;
import com.openai.models.ChatCompletionMessageParam;
import com.openai.models.ChatCompletionMessageToolCall;
import com.openai.models.ChatCompletionTool;
import com.openai.models.ChatCompletionToolMessageParam;
import com.openai.models.ChatCompletionUserMessageParam;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lesson5RunSimple - Skills
 *
 * Two-layer skill injection that avoids bloating the system prompt:
 *   Layer 1 (cheap): skill names in system prompt (~100 tokens/skill)
 *   Layer 2 (on demand): full skill body in tool_result
 *
 * Key insight: "Don't put everything in the system prompt. Load on demand."
 */
@Component
public class Lesson5RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson5RunSimple.class);
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.base-url}")
    private String baseUrl;

    @Value("${openai.model}")
    private String modelName;

    @Value("${openai.system-prompt}")
    private String systemPrompt;

    @Value("${proxy.host}")
    private String proxyHost;

    @Value("${proxy.port}")
    private int proxyPort;

    private Path workDir;
    private SkillLoader skillLoader;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson5 (Skill Loading) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        Path skillsDir = workDir.resolve("skills");
        skillLoader = new SkillLoader(skillsDir);

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        List<ChatCompletionTool> tools = createTools();
        Map<String, ToolHandler> handlers = createHandlers();

        // Layer 1: skill metadata injected into system prompt
        String skillDescriptions = skillLoader.getDescriptions();
        String sysPrompt = "You are a coding agent at " + workDir + ".\n" +
                "Use load_skill to access specialized knowledge before tackling unfamiliar topics.\n\n" +
                "Skills available:\n" + skillDescriptions;

        agentLoop(client, messages, tools, handlers, sysPrompt);
    }

    private void agentLoop(OpenAIClient client, List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers, String sysPrompt) {
        while (true) {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .messages(messages)
                    .tools(tools)
                    .addSystemMessage(sysPrompt);

            ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
            ChatCompletion.Choice choice = completion.choices().get(0);
            ChatCompletionMessage assistantMessage = choice.message();

            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            if (!ChatCompletion.Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason())) {
                assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
                break;
            }

            if (assistantMessage.toolCalls().isPresent()) {
                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    ChatCompletionMessageToolCall.Function function = toolCall.function();
                    String toolName = function.name();
                    String arguments = function.arguments();

                    log.info("Tool call: {} with args: {}", toolName, arguments);

                    String output = executeTool(handlers, toolName, arguments);
                    log.info("Output (truncated): {}", output.length() > 200 ? output.substring(0, 200) : output);

                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.id())
                            .content(output)
                            .build()));
                }
            }
        }
    }

    // -- SkillLoader: scan skills/<name>/SKILL.md with YAML frontmatter --
    static class SkillLoader {
        private final Map<String, Skill> skills = new HashMap<>();

        public SkillLoader(Path skillsDir) {
            loadAll(skillsDir);
        }

        private void loadAll(Path skillsDir) {
            if (!Files.exists(skillsDir)) return;

            try {
                Files.walk(skillsDir)
                        .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .sorted()
                        .forEach(this::loadSkill);
            } catch (Exception e) {
                log.warn("Error loading skills: {}", e.getMessage());
            }
        }

        private void loadSkill(Path skillFile) {
            try {
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

                String name = meta.getOrDefault("name", skillFile.getParent().getFileName().toString());
                skills.put(name, new Skill(meta, body, skillFile.toString()));

            } catch (Exception e) {
                log.warn("Error loading skill {}: {}", skillFile, e.getMessage());
            }
        }

        // Layer 1: short descriptions for the system prompt
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

        // Layer 2: full skill body returned in tool_result
        public String getContent(String name) {
            Skill skill = skills.get(name);
            if (skill == null) {
                return "Error: Unknown skill '" + name + "'. Available: " + String.join(", ", skills.keySet());
            }
            return "<skill name=\"" + name + "\">\n" + skill.body + "\n</skill>";
        }
    }

    static class Skill {
        Map<String, String> meta;
        String body;
        String path;

        Skill(Map<String, String> meta, String body, String path) {
            this.meta = meta;
            this.body = body;
            this.path = path;
        }
    }

    // -- Tool implementations --
    private Path safePath(String p) {
        Path path = resolveWorkspacePath(workDir, p, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")).normalize();
        if (!path.startsWith(workDir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + p);
        }
        return path;
    }

    static Path resolveWorkspacePath(Path workDir, String inputPath, boolean windows) {
        String normalizedInput = inputPath;
        if (windows) {
            var inputMatcher = WINDOWS_ABSOLUTE_PATH.matcher(inputPath);
            var workDirMatcher = WINDOWS_ABSOLUTE_PATH.matcher(workDir.toString().replace("\\", "/"));
            if (inputMatcher.matches() && workDirMatcher.matches()
                    && inputMatcher.group(1).equalsIgnoreCase(workDirMatcher.group(1))) {
                String workDirRest = workDirMatcher.group(2).replace("\\", "/");
                String inputRest = inputMatcher.group(2).replace("\\", "/");
                String workDirPrefix = workDirRest.endsWith("/") ? workDirRest : workDirRest + "/";

                if (inputRest.equalsIgnoreCase(workDirRest)) {
                    return workDir;
                }
                if (inputRest.regionMatches(true, 0, workDirPrefix, 0, workDirPrefix.length())) {
                    return workDir.resolve(inputRest.substring(workDirPrefix.length()));
                }
            }
        } else {
            var matcher = WINDOWS_ABSOLUTE_PATH.matcher(inputPath);
            if (matcher.matches()) {
                String drive = matcher.group(1).toLowerCase(Locale.ROOT);
                String rest = matcher.group(2).replace("\\", "/");
                normalizedInput = "/mnt/" + drive + "/" + rest;
            }
        }

        Path candidate = Paths.get(normalizedInput);
        return candidate.isAbsolute() ? candidate : workDir.resolve(normalizedInput);
    }

    private String runBash(String command) {
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String output = reader.lines().collect(Collectors.joining("\n")).trim();
                return output.isEmpty() ? "(no output)" : truncate(output, 50000);
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runRead(String path, Integer limit) {
        try {
            List<String> lines = Files.readAllLines(safePath(path), StandardCharsets.UTF_8);
            int originalSize = lines.size();
            if (limit != null && limit < originalSize) {
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (originalSize - limit) + " more lines)");
            }
            return truncate(String.join("\n", lines), 50000);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runWrite(String path, String content) {
        try {
            Path fp = safePath(path);
            Files.createDirectories(fp.getParent());
            Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
            return "Wrote " + content.length() + " bytes";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runEdit(String path, String oldText, String newText) {
        try {
            Path fp = safePath(path);
            String content = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }
            content = content.replaceFirst(escapeRegex(oldText), newText);
            Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String escapeRegex(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$")
                .replace(".", "\\.").replace("*", "\\*")
                .replace("+", "\\+").replace("?", "\\?")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)")
                .replace("{", "\\{").replace("}", "\\}")
                .replace("|", "\\|").replace("^", "\\^");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String executeTool(Map<String, ToolHandler> handlers, String toolName, String arguments) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            return "Unknown tool: " + toolName;
        }
        try {
            Map<String, Object> args = parseArguments(arguments);
            return handler.execute(args);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // -- Tool definitions --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("bash")
                        .description("Run a shell command.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("command", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("read_file")
                        .description("Read file contents.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "limit", Map.of("type", "integer")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("write_file")
                        .description("Write content to file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "content", Map.of("type", "string")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "content")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("edit_file")
                        .description("Replace exact text in file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "old_text", Map.of("type", "string"),
                                        "new_text", Map.of("type", "string")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "old_text", "new_text")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("load_skill")
                        .description("Load specialized knowledge by name.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "name", Map.of("type", "string", "description", "Skill name to load")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("name")))
                                .build())
                        .build())
                .build());

        return tools;
    }

    // -- Tool dispatch map --
    private Map<String, ToolHandler> createHandlers() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        handlers.put("bash", args -> runBash((String) args.get("command")));
        handlers.put("read_file", args -> runRead((String) args.get("path"), (Integer) args.get("limit")));
        handlers.put("write_file", args -> runWrite((String) args.get("path"), (String) args.get("content")));
        handlers.put("edit_file", args -> runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text")));
        handlers.put("load_skill", args -> skillLoader.getContent((String) args.get("name")));
        return handlers;
    }

    // Simple JSON argument parser
    private Map<String, Object> parseArguments(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;

        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        result.put(key, value.substring(1, value.length() - 1).replace("\\\"", "\""));
                    } else if (value.matches("-?\\d+")) {
                        result.put(key, Integer.parseInt(value));
                    } else {
                        result.put(key, value);
                    }
                }
            }
        }
        return result;
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(Map<String, Object> args) throws Exception;
    }
}
