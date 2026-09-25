package com.ll.hirehub.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 网关限流规则的反序列化器（见 §8.3 / 踩坑记录 #20）。
 * <p>
 * <b>为什么必须自己写这个 bean</b>：
 * {@code spring-cloud-starter-alibaba-sentinel} 的 {@code SentinelDataSourceHandler} 只会为
 * <b>内置</b>规则类型（flow / degrade / param-flow / system / authority）注册 JSON converter。
 * 网关用的是 {@code gw-flow}（{@code GatewayFlowRule}），不在内置名单里，
 * 于是 Spring 解析 Nacos datasource 的 {@code converter} 属性时找不到 bean：
 * <pre>
 *   ERROR SentinelDataSourceHandler : DataSource flow build error:
 *     Cannot resolve reference to bean 'sentinel-json-gw-flow-converter'
 *   Caused by: NoSuchBeanDefinitionException: No bean named 'sentinel-json-gw-flow-converter'
 * </pre>
 * 后果很隐蔽：<b>datasource 创建失败 → 规则一条都没加载 → 限流静默失效</b>，
 * 而异常被 {@code SentinelDataSourceHandler.afterSingletonsInstantiated} 吞掉，
 * 网关照常启动、不报错。表现就是"Nacos 里规则配得好好的，压测却全放行"。
 * <p>
 * bean 名必须严格是 {@code sentinel-json-<ruleType>-converter}（ruleType 即 yml 里的
 * {@code spring.cloud.sentinel.datasource.flow.rule-type: gw-flow}），
 * 这是 SCA 拼名字的约定，改名就再次失效。
 * <p>
 * <b>返回类型必须是 {@code Set} 而不是 {@code List}</b>：
 * {@code GatewayRuleManager.register2Property(SentinelProperty<Set<GatewayFlowRule>>)} 要的是 Set，
 * 给的 List 会编译不过（详见踩坑记录 #20 续集）。
 */
@Configuration
public class SentinelGwFlowConverterConfig {

    @Bean("sentinel-json-gw-flow-converter")
    public Converter<String, Set<GatewayFlowRule>> gwFlowRuleConverter(ObjectMapper objectMapper) {
        return source -> {
            try {
                return objectMapper.readValue(source, new TypeReference<Set<GatewayFlowRule>>() {
                });
            } catch (Exception e) {
                // 解析失败要抛出：规则解析不出来时宁可启动时报错，也不要静默地"没有规则"
                throw new IllegalStateException("解析网关限流规则失败（检查 Nacos 里 hirehub-gateway-flow 的 JSON 格式）: "
                        + e.getMessage(), e);
            }
        };
    }
}
