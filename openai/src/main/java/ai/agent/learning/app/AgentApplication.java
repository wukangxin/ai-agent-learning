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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

@SpringBootApplication
@ComponentScan(basePackages = "ai.agent.learning")
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    public static void main(String[] args) {
        args = new String[2];
        fillArgs(args);
        loadLocalEnv();
        SpringApplication.run(AgentApplication.class, args);
    }

    private static void fillArgs(String[] args) {
        Scanner scanner = new Scanner(System.in);
        if (scanner.hasNextLine()) {
            args[0] = scanner.nextLine();
        }
        if (scanner.hasNextLine()) {
            args[1] = scanner.nextLine();
        }
    }

    private static void loadLocalEnv() {
        Path envPath = Paths.get("env.local");
        if (Files.exists(envPath)) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envPath.toFile())) {
                props.load(fis);
                props.forEach((key, value) -> {
                    String sKey = (String) key;
                    String sValue = (String) value;
                    System.setProperty(sKey, sValue);
                    
                    // Normalize to spring properties
                    if ("OPENAI_API_KEY".equals(sKey)) System.setProperty("openai.api-key", sValue);
                    if ("OPENAI_BASE_URL".equals(sKey)) System.setProperty("openai.base-url", sValue);
                    if ("OPENAI_MODEL".equals(sKey)) System.setProperty("openai.model", sValue);
                    if ("OPENAI_SYSTEM_PROMPT".equals(sKey)) System.setProperty("openai.system-prompt", sValue);
                    if ("PROXY_HOST".equals(sKey)) System.setProperty("proxy.host", sValue);
                    if ("PROXY_PORT".equals(sKey)) System.setProperty("proxy.port", sValue);
                });
                log.info("Loaded environment variables from env.local");
            } catch (IOException e) {
                log.error("Failed to load env.local: {}", e.getMessage());
            }
        }
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            if (args.length > 1) {
                String lesson = args[0];
                String userPrompt = args[1];
                Map<String, RunSimple> beans = ctx.getBeansOfType(RunSimple.class);
                RunSimple runner = beans.values().stream()
                        .filter(r -> r.getClass().getSimpleName().toLowerCase().contains(lesson.toLowerCase()))
                        .findFirst()
                        .orElse(null);

                if (runner != null) {
                    runner.run(userPrompt);
                } else {
                    log.warn("No runner found for: {}", lesson);
                }
            }
        };
    }
}
