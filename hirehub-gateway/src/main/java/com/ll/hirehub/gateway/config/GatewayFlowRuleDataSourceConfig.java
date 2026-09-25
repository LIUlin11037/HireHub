package com.ll.hirehub.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.nacos.api.PropertyKeyConst;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.Properties;

/**
 * 把 Nacos 里的网关限流规则<b>手工</b>接进 {@link GatewayRuleManager}（见 §8.3 / 踩坑记录 #20）。
 * <p>
 * <b>为什么不用 SCA 自带的 datasource 配置</b>：
 * yml 里那段 {@code spring.cloud.sentinel.datasource.flow.nacos(rule-type: gw-flow)} 看似能配，
 * 但实测（2026-09-25）证明它<b>只建得起 datasource、不会把规则注册进 GatewayRuleManager</b>：
 * <ul>
 *   <li>缺 {@code sentinel-json-gw-flow-converter} → 补上后错误消失（datasource 建成功）；</li>
 *   <li>Nacos 里规则内容改成规范的 {@code GatewayFlowRule} 并热加载 → 依然 0 个 429；</li>
 *   <li>结论：规则被拉下来了，但<b>没有交给 GatewayRuleManager</b>，于是限流静默失效。</li>
 * </ul>
 * 所以这里绕开 SCA 的 handler，直接用 Sentinel 原生 API 接线：Nacos 数据源 → GatewayRuleManager。
 * 这条路由是官方文档里"网关规则持久化"的标准写法，不依赖 SCA 对 gw-flow 的支持程度。
 * <p>
 * yml 里原来那段 SCA 配置<b>保留不动</b>：它不生效也无害，留着可以对照排查；
 * 真正说话的是本类注册的这个数据源。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GatewayFlowRuleDataSourceConfig {

    private final Converter<String, Set<GatewayFlowRule>> gwFlowRuleConverter;

    @Value("${hirehub.sentinel.nacos.server-addr:localhost:8848}")
    private String serverAddr;

    @Value("${hirehub.sentinel.nacos.username:nacos}")
    private String username;

    @Value("${hirehub.sentinel.nacos.password:nacos}")
    private String password;

    @Value("${hirehub.sentinel.nacos.group-id:DEFAULT_GROUP}")
    private String groupId;

    @Value("${hirehub.sentinel.nacos.data-id:hirehub-gateway-flow}")
    private String dataId;

    @PostConstruct
    public void registerGatewayFlowRules() {
        try {
            Properties properties = new Properties();
            properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
            // Nacos 已开启鉴权，NacosDataSource 必须带客户端账号（与各服务 discovery 用的是同一套）
            properties.setProperty(PropertyKeyConst.USERNAME, username);
            properties.setProperty(PropertyKeyConst.PASSWORD, password);

            // NacosDataSource 只有一个类型参数 T（继承 AbstractDataSource<String, T>，源类型固定 String）；
            // 且 GatewayRuleManager.register2Property 要的是 SentinelProperty<Set<GatewayFlowRule>>，
            // 所以这里必须是 Set —— 用 List 会报「不兼容的类型」。
            NacosDataSource<Set<GatewayFlowRule>> dataSource =
                    new NacosDataSource<>(properties, groupId, dataId, gwFlowRuleConverter);
            GatewayRuleManager.register2Property(dataSource.getProperty());
            log.info("[Sentinel] 已把 Nacos 网关限流规则接入 GatewayRuleManager: dataId={} group={} server={}",
                    dataId, groupId, serverAddr);
        } catch (Exception e) {
            // 不抛出：限流配不上不该让网关起不来，但必须 ERROR 到日志（限流会静默失效）
            log.error("[Sentinel] 接入网关限流规则失败，限流将不生效: {}", e.getMessage(), e);
        }
    }
}
