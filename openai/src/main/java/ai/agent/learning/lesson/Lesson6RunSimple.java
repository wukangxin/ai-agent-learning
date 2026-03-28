package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessage;
import com.openai.models.ChatCompletionAssistantMessageParam;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lesson6RunSimple - Compact
 *
 * Three-layer compression pipeline so the agent can work forever:
 *   Layer 1: micro_compact (silent, every turn) - replace old tool results with placeholders
 *   Layer 2: auto_compact - save transcript, summarize, replace messages
 *   Layer 3: compact tool - model calls compact -> immediate summarization
 *
 * Key insight: "The agent can forget strategically and keep working forever."
 */
@Component
public class Lesson6RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson6RunSimple.class);

    private static final int DEFAULT_AUTO_COMPACT_THRESHOLD = 12000;
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
    private Path transcriptDir;
    private OpenAIClient client;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson6 (Context Compact) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        transcriptDir = workDir.resolve(".transcripts");

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        List<ChatCompletionTool> tools = createTools();
        Map<String, ToolHandler> handlers = createHandlers();

        agentLoop(messages, tools, handlers);
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers) {
        while (true) {
            // Layer 1: micro_compact before each LLM call
            microCompact(messages);

            // Layer 2: auto_compact if token estimate exceeds threshold
            if (estimateTokens(messages) > DEFAULT_AUTO_COMPACT_THRESHOLD) {
                log.info("[auto_compact triggered]");
                List<ChatCompletionMessageParam> compressed = autoCompact(messages);
                messages.clear();
                messages.addAll(compressed);
            }

            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .messages(messages)
                    .tools(tools);

            String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                    ? systemPrompt
                    : "You are a coding agent at " + workDir + ". Use tools to solve tasks. " + platformGuidance();
            paramsBuilder.addSystemMessage(sysPrompt);

            ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
            ChatCompletion.Choice choice = completion.choices().get(0);
            ChatCompletionMessage assistantMessage = choice.message();

            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            if (!ChatCompletion.Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason())) {
                assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
                break;
            }

            if (assistantMessage.toolCalls().isPresent()) {
                boolean manualCompact = false;

                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    ChatCompletionMessageToolCall.Function function = toolCall.function();
                    String toolName = function.name();
                    String arguments = function.arguments();

                    log.info("Tool call: {} with args: {}", toolName, arguments);

                    if ("compact".equals(toolName)) {
                        manualCompact = true;
                    }

                    String output = executeTool(handlers, toolName, arguments);
                    log.info("Output (truncated): {}", output.length() > 200 ? output.substring(0, 200) : output);

                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.id())
                            .content(output)
                            .build()));
                }

                // Layer 3: manual compact triggered by the compact tool
                if (manualCompact) {
                    log.info("[manual compact]");
                    List<ChatCompletionMessageParam> compressed = autoCompact(messages);
                    messages.clear();
                    messages.addAll(compressed);
                }
            }
        }
    }

    // -- Token estimation: rough count ~4 chars per token --
    private int estimateTokens(List<ChatCompletionMessageParam> messages) {
        return messages.toString().length() / 4;
    }

    // -- Layer 1: micro_compact - replace old tool results with placeholders --
    private void microCompact(List<ChatCompletionMessageParam> messages) {
        // Truncate tool results older than the last 6 messages to save context space.
        // Recent results stay intact so the model can still reason about them.
        int cutoff = Math.max(0, messages.size() - 6);
        for (int i = 0; i < cutoff; i++) {
            ChatCompletionMessageParam msg = messages.get(i);
            if (msg.isTool()) {
                ChatCompletionToolMessageParam tool = msg.asTool();
                String content = tool.content().isText() ? tool.content().asText() : tool.content().toString();
                if (content.length() > 200) {
                    messages.set(i, ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tool.toolCallId())
                            .content(content.substring(0, 200) + "... [truncated]")
                            .build()));
                }
            }
        }
    }

    // -- Layer 2: auto_compact - save transcript, summarize, replace messages --
    private List<ChatCompletionMessageParam> autoCompact(List<ChatCompletionMessageParam> messages) {
        try {
            // Save full transcript to disk
            Files.createDirectories(transcriptDir);
            Path transcriptPath = transcriptDir.resolve("transcript_" + Instant.now().getEpochSecond() + ".jsonl");

            StringBuilder transcriptContent = new StringBuilder();
            for (ChatCompletionMessageParam msg : messages) {
                transcriptContent.append(msg.toString()).append("\n");
            }
            Files.write(transcriptPath, transcriptContent.toString().getBytes(StandardCharsets.UTF_8));
            log.info("[transcript saved: {}]", transcriptPath);

            // Ask LLM to summarize
            String conversationText = messages.toString();
            if (conversationText.length() > 80000) {
                conversationText = conversationText.substring(0, 80000);
            }

            ChatCompletionCreateParams summaryParams = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .addUserMessage("Summarize this conversation for continuity. Include:\n" +
                            "1) What was accomplished\n" +
                            "2) Current state\n" +
                            "3) Key decisions made\n" +
                            "Be concise but preserve critical details.\n\n" + conversationText)
                    .build();

            ChatCompletion summaryCompletion = client.chat().completions().create(summaryParams);
            String summary = summaryCompletion.choices().get(0).message().content().orElse("(no summary)");

            // Replace all messages with compressed summary
            List<ChatCompletionMessageParam> compressed = new ArrayList<>();
            compressed.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(
                    "[Conversation compressed. Transcript: " + transcriptPath.getFileName() + "]\n\n" + summary).build()));
            compressed.add(ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder().content("Understood. I have the context from the summary. Continuing.").build()));

            return compressed;

        } catch (Exception e) {
            log.error("Error during auto_compact: {}", e.getMessage());
            return messages;
        }
    }

    // -- Tool implementations --
    private Path safePath(String p) {
        Path path = resolveWorkspacePath(workDir, p, isWindows()).normalize();
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
            ProcessBuilder pb = new ProcessBuilder(buildShellCommand(command, isWindows()));
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
            if (limit != null && limit < lines.size()) {
                lines = lines.subList(0, limit);
                lines.add("... (" + (lines.size() - limit) + " more)");
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

    static List<String> buildShellCommand(String command, boolean windows) {
        return windows
                ? List.of("powershell.exe", "-NoProfile", "-Command", command)
                : List.of("bash", "-lc", command);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String platformGuidance() {
        return isWindows()
                ? "This workspace is running on Windows. Use PowerShell syntax and Windows paths when calling bash."
                : "This workspace is running on Linux. Use bash syntax and POSIX paths when calling bash.";
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
                        .name("compact")
                        .description("Trigger manual conversation compression.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "focus", Map.of("type", "string", "description", "What to preserve in the summary")
                                )))
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
        handlers.put("compact", args -> "Manual compression requested.");
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
