package com.ll.hirehub.job.search;

/**
 * 自然语言 → 结构化搜索意图（见 D-29）。
 * <p>
 * 与 {@code RealNameVerifier}（D-16）、{@code CompanyVerifier}（D-18）同一套策略接口模式：
 * <ul>
 *   <li>{@link MockSearchQueryParser}　开发默认 + 降级兜底：本地规则粗抽</li>
 *   <li>{@code LlmSearchQueryParser}　正式实现：调大模型（Q-10 待定）</li>
 * </ul>
 */
public interface SearchQueryParser {

    SearchIntent parse(String query);
}
