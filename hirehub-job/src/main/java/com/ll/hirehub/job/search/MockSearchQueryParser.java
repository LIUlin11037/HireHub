package com.ll.hirehub.job.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言搜索的 Mock 解析器（见 D-29）。
 * <p>
 * 规则粗抽城市 / 学历 / 经验 / 薪资，剩余文本作为关键词。
 * 这是「无 Key / 断网」时的降级兜底；正式版走 {@code LlmSearchQueryParser}。
 * 规则解析的局限（"3万以上""本科及以上""25-40k"这类歧义）正是 D-29 选大模型而非纯规则的原因。
 */
@Component
@ConditionalOnProperty(name = "hirehub.search.parser", havingValue = "MOCK", matchIfMissing = true)
public class MockSearchQueryParser implements SearchQueryParser {

    private static final List<String> CITIES = List.of(
            "杭州", "北京", "上海", "深圳", "广州", "成都", "武汉", "南京", "苏州", "西安",
            "厦门", "长沙", "重庆", "郑州", "天津", "合肥", "青岛", "济南", "福州", "南昌");

    private static final List<String> EDUCATIONS = List.of("博士", "硕士", "本科", "大专", "高中", "不限");

    private static final List<String> EXPERIENCES = List.of("应届", "1-3年", "3-5年", "5-10年", "10年以上", "三年以上", "五年以上");

    private static final Pattern SALARY = Pattern.compile(
            "(\\d+)\\s*([kK千万])(?:\\s*[-~到至]\\s*(\\d+)\\s*([kK千万]))?(?:\\s*(以上|以下|以内))?");

    @Override
    public SearchIntent parse(String query) {
        SearchIntent intent = new SearchIntent();
        if (query == null || query.isBlank()) {
            intent.setKeyword("");
            return intent;
        }

        for (String city : CITIES) {
            if (query.contains(city)) {
                intent.setCity(city);
                break;
            }
        }
        for (String edu : EDUCATIONS) {
            if (query.contains(edu)) {
                intent.setEducation(edu);
                break;
            }
        }
        for (String exp : EXPERIENCES) {
            if (query.contains(exp)) {
                intent.setExperience(exp);
                break;
            }
        }
        parseSalary(query, intent);

        // 剩余文本 = 关键词
        String kw = query;
        if (intent.getCity() != null) {
            kw = kw.replace(intent.getCity(), " ");
        }
        if (intent.getEducation() != null) {
            kw = kw.replace(intent.getEducation(), " ");
        }
        if (intent.getExperience() != null) {
            kw = kw.replace(intent.getExperience(), " ");
        }
        kw = SALARY.matcher(kw).replaceAll(" ");
        kw = kw.replaceAll("\\s+", " ").trim();
        intent.setKeyword(kw.isEmpty() ? query : kw);
        return intent;
    }

    private void parseSalary(String q, SearchIntent intent) {
        Matcher m = SALARY.matcher(q);
        if (!m.find()) {
            return;
        }
        int lo = toYuan(m.group(1), m.group(2));
        Integer hi = (m.group(3) != null) ? toYuan(m.group(3), m.group(4)) : null;
        String direction = m.group(5);
        if ("以上".equals(direction)) {
            intent.setSalaryMin(lo);
        } else if ("以下".equals(direction) || "以内".equals(direction)) {
            intent.setSalaryMax(lo);
        } else if (hi != null) {
            intent.setSalaryMin(Math.min(lo, hi));
            intent.setSalaryMax(Math.max(lo, hi));
        } else {
            intent.setSalaryMin(lo);
        }
    }

    private int toYuan(String num, String unit) {
        int n = Integer.parseInt(num);
        return "万".equals(unit) ? n * 10000 : n * 1000;   // k / 千
    }
}
