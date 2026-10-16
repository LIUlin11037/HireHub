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
 *   <li>调用失败 / 超时 / 无 Key → 降级为「原始关键词」查询，保证搜索不瘫；</li>
 *   <li><b>输出白名单校验（审计 R-06 / D-29）</b>：city/education/experience 直接进 ES term 过滤，
 *       提示词注入可让模型输出非候选值污染筛选条件——三个字段必须过候选集校验，非法值置 null。</li>
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

    // ---------------- 审计 R-06：LLM 输出白名单（与提示词候选值严格一致） ----------------

    /** 学历候选值（与 SYSTEM_PROMPT 一致） */
    private static final java.util.Set<String> EDUCATION_WHITELIST = java.util.Set.of(
            "不限", "高中", "大专", "本科", "硕士", "博士");

    /** 经验候选值（与 SYSTEM_PROMPT 一致） */
    private static final java.util.Set<String> EXPERIENCE_WHITELIST = java.util.Set.of(
            "应届", "1-3年", "3-5年", "5-10年", "10年以上");

    /**
     * 城市候选值：ES city 是 keyword 精确匹配，只放行平台支持的标准城市名（不带「市」）。
     * 模型输出的同义改写（"杭州市"）或注入的任意串都被挡在这里。
     */
    private static final java.util.Set<String> CITY_WHITELIST = java.util.Set.of(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州", "成都", "重庆", "武汉",
            "西安", "天津", "长沙", "郑州", "合肥", "青岛", "济南", "大连", "宁波", "厦门",
            "福州", "无锡", "佛山", "东莞", "珠海", "昆明", "贵阳", "南昌", "石家庄", "太原",
            "哈尔滨", "长春", "沈阳", "兰州", "乌鲁木齐", "呼和浩特", "南宁", "海口", "西宁", "银川",
            "拉萨", "香港", "澳门", "台北");

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
            intent.setSalaryMin(intOrNull(j, "salaryMin"));
            intent.setSalaryMax(intOrNull(j, "salaryMax"));
            // 审计 R-06：进 ES term 过滤的字段必须过白名单，非法值（同义改写/提示词注入）置 null
            intent.setCity(whitelist(textOrNull(j, "city"), CITY_WHITELIST, "city"));
            intent.setEducation(whitelist(textOrNull(j, "education"), EDUCATION_WHITELIST, "education"));
            intent.setExperience(whitelist(textOrNull(j, "experience"), EXPERIENCE_WHITELIST, "experience"));
            // ★ 成功必须留痕：否则"LLM 到底跑没跑"从外部完全看不出来 ——
            // 降级路径有 warn，成功路径却没有日志，于是"接口返回了结果"既可能是真调了模型，
            // 也可能是静默降级成了关键词搜索。可解释、可降级的前提是**可观测**。
            log.info("[NL 搜索] LLM 解析成功: keyword={} city={} salary={}-{} education={} experience={}",
                    intent.getKeyword(), intent.getCity(), intent.getSalaryMin(), intent.getSalaryMax(),
                    intent.getEducation(), intent.getExperience());
        } catch (Exception e) {
            log.warn("LLM 解析异常，降级为原始关键词: {}", e.getMessage());
            intent.setKeyword(query);
        }
        return intent;
    }

    private String textOrNull(JsonNode n, String field) {
        return (n.has(field) && !n.get(field).isNull()) ? n.get(field).asText() : null;
    }

    /** 白名单校验（审计 R-06）：值不在候选集内记 warn 并置 null，不让模型输出直接污染 ES 过滤条件 */
    private String whitelist(String value, java.util.Set<String> candidates, String field) {
        if (value == null) {
            return null;
        }
        if (candidates.contains(value)) {
            return value;
        }
        log.warn("[NL 搜索] LLM 输出的 {} 不在候选值内，已丢弃: {}", field, value);
        return null;
    }

    private Integer intOrNull(JsonNode n, String field) {
        return (n.has(field) && !n.get(field).isNull()) ? n.get(field).asInt() : null;
    }
}
