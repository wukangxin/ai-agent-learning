package ai.agent.learning.lesson;

import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * 测试基类，提供公共的测试设置
 */
public abstract class BaseLessonTest {

    @TempDir
    protected Path tempDir;

    protected static final String TEST_API_KEY = "test-api-key";
    protected static final String TEST_BASE_URL = "https://api.test.com/v1";
    protected static final String TEST_MODEL = "gpt-4";
    protected static final String TEST_SYSTEM_PROMPT = "You are a test assistant.";
    protected static final String TEST_PROXY_HOST = "127.0.0.1";
    protected static final int TEST_PROXY_PORT = 7890;

    /**
     * 使用反射设置对象的私有字段
     */
    protected void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * 初始化Lesson实例的通用配置
     */
    protected void initLesson(Object lesson) throws Exception {
        setField(lesson, "apiKey", TEST_API_KEY);
        setField(lesson, "baseUrl", TEST_BASE_URL);
        setField(lesson, "modelName", TEST_MODEL);
        setField(lesson, "systemPrompt", TEST_SYSTEM_PROMPT);
        setField(lesson, "proxyHost", TEST_PROXY_HOST);
        setField(lesson, "proxyPort", TEST_PROXY_PORT);
    }
}
