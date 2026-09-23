package com.ll.hirehub.job.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 自然语言搜索的正式解析器（见 D-29，模型 Qwen-Plus）。
 * <p>
 * 走通义千问 DashScope 的 OpenAI 兼容接口 + {@code response_format=json_object}，
 * 提示词约束输出结构化 JSON，再映射成 {@link SearchIntent}。
 * <p>
 * 安全与降级：
 * <ul>
 *   <li>API Key 走环境变量 {@code DASHSCOPE_API_KEY}，绝不进 Git（同 D-18）；</li>
 *   <li>调用失败 / 超时 / 无 Key → 降级为「原始关键词」查询，保证搜索不瘫。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hirehub.search.parser", havingValue = "LLM")
public class LlmSearchQueryParser implements SearchQueryParser {

    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen3.7-plus";
    private static final String SYSTEM_PROMPT = """
            你是招聘平台的搜索意图解析器。把用户的自然语言搜索请求解析成一个 JSON 对象，
            只输出 JSON，不要任何解释、不要 markdown 代码块。

            输出字段固定为（字段名不要增删）：
            {
              "keyword": "用于 ES multi_match 全文检索的关键词（岗位名/技能/公司名/行业等）",
              "city": "城市，必须是标准城市名且不带「市」字，如 杭州/北京/上海/深圳/广州；未提及则 null",
              "salaryMin": "最低月薪整数（元/月）；未提及则 null",
              "salaryMax": "最高月薪整数（元/月）；未提及则 null",
              "education": "学历，只能从 [不限, 高中, 大专, 本科, 硕士, 博士] 中选一个；未提及则 null",
              "experience": "经验，只能从 [应届, 1-3年, 3-5年, 5-10年, 10年以上] 中选一个；未提及则 null"
            }

            硬性规则：
            1. city / education / experience 必须严格从上面的候选值里选，保证与 ES 里 keyword 字段的取值完全一致，
               不要输出「杭州市」「3年以上」这类同义改写。
            2. 薪资统一折算成「元/月」整数：25-40k → 25000/40000；3万以上 → salaryMin=30000；2万以下 → salaryMax=20000。
            3. 未提及的字段输出 null，不要臆造。
            4. 其余语义（岗位、技能、公司、行业等）合并进 keyword。
            """;

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public LlmSearchQueryParser(@Value("${DASHSCOPE_API_KEY:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchIntent parse(String query) {
        SearchIntent intent = new SearchIntent();
        if (apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY 未配置，LLM 解析降级为原始关键词");
            intent.setKeyword(query);
            return intent;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", MODEL);
            body.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
            messages.addObject().put("role", "user").put("content", query);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LLM 解析失败 status={}，降级为原始关键词", response.statusCode());
                intent.setKeyword(query);
                return intent;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode j = objectMapper.readTree(content);
            intent.setKeyword(textOrNull(j, "keyword"));
            intent.setCity(textOrNull(j, "city"));
            intent.setSalaryMin(intOrNull(j, "salaryMin"));
            intent.setSalaryMax(intOrNull(j, "salaryMax"));
            intent.setEducation(textOrNull(j, "education"));
            intent.setExperience(textOrNull(j, "experience"));
        } catch (Exception e) {
            log.warn("LLM 解析异常，降级为原始关键词: {}", e.getMessage());
            intent.setKeyword(query);
        }
        return intent;
    }

    private String textOrNull(JsonNode n, String field) {
        return (n.has(field) && !n.get(field).isNull()) ? n.get(field).asText() : null;
    }

    private Integer intOrNull(JsonNode n, String field) {
        return (n.has(field) && !n.get(field).isNull()) ? n.get(field).asInt() : null;
    }
}
