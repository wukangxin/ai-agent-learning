package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Component
public class Lesson0RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson0RunSimple.class);

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

    @Override
    public void run(String userPrompt) {
        log.info("Starting request with model: {} via proxy {}:{}", modelName, proxyHost, proxyPort);
        log.info("System Prompt: {}", systemPrompt);
        log.info("User Prompt: {}", userPrompt);

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

        // Using official OpenAI Java SDK
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(modelName));

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            paramsBuilder.addSystemMessage(systemPrompt);
        }
        paramsBuilder.addUserMessage(userPrompt);

        ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
        
        String response = completion.choices().get(0).message().content().orElse("No response");
        log.info("Response received: {}", response);
    }
}
