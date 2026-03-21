package ai.agent.learning.app;

import ai.agent.learning.base.RunSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = "ai.agent.learning")
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    private static final String ENV_FILE = "env.local";
    private static final int MAX_PARENT_DEPTH = 4;

    private static final Map<String, String> ENV_TO_SPRING = Map.of(
            "OPENAI_API_KEY", "openai.api-key",
            "OPENAI_BASE_URL", "openai.base-url",
            "OPENAI_MODEL", "openai.model",
            "OPENAI_SYSTEM_PROMPT", "openai.system-prompt",
            "PROXY_HOST", "proxy.host",
            "PROXY_PORT", "proxy.port"
    );

    public static void main(String[] args) {
        loadLocalEnv();
        SpringApplication.run(AgentApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            Map<String, String> parsed = parseArgs(args);
            String lesson = parsed.get("lesson");
            String prompt = parsed.get("prompt");

            if (lesson == null || prompt == null) {
                log.info("Usage: --lesson=<name> --prompt=<text>");
                return;
            }

            log.info("args: {}, {}", lesson, prompt);

            RunSimple runner = findRunner(ctx, lesson);
            if (runner != null) {
                runner.run(prompt);
            } else {
                String available = ctx.getBeansOfType(RunSimple.class).values().stream()
                        .map(r -> r.getClass().getSimpleName())
                        .collect(Collectors.joining(", "));
                log.warn("No runner found for '{}'. Available: [{}]", lesson, available);
            }
        };
    }

    private static RunSimple findRunner(ApplicationContext ctx, String lesson) {
        String target = lesson.toLowerCase() + "runsimple";
        return ctx.getBeansOfType(RunSimple.class).values().stream()
                .filter(r -> r.getClass().getSimpleName().toLowerCase().contains(target))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> parseArgs(String[] args) {
        return Stream.of(args)
                .filter(a -> a.startsWith("--") && a.contains("="))
                .collect(Collectors.toMap(
                        a -> a.substring(2, a.indexOf('=')),
                        a -> a.substring(a.indexOf('=') + 1),
                        (a, b) -> b
                ));
    }

    private static void loadLocalEnv() {
        Path envPath = findFileUpward(ENV_FILE, MAX_PARENT_DEPTH);
        if (envPath == null) return;

        Properties props = new Properties();
        try (var in = Files.newInputStream(envPath)) {
            props.load(in);
        } catch (IOException e) {
            log.error("Failed to load {}: {}", envPath, e.getMessage());
            return;
        }

        props.forEach((key, value) -> {
            String k = (String) key, v = (String) value;
            System.setProperty(k, v);
            String springKey = ENV_TO_SPRING.get(k);
            if (springKey != null) {
                System.setProperty(springKey, v);
            }
        });
        log.info("Loaded env from {}", envPath);
    }

    private static Path findFileUpward(String filename, int maxDepth) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < maxDepth && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(filename);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
}
