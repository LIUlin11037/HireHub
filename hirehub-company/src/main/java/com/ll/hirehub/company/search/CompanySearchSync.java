package com.ll.hirehub.company.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.MqConsumeLog;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.MqConsumeLogMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 企业 MySQL → ES 的 MQ 异步同步（company_index，见 §6.3 / D-09）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanySearchSync implements ApplicationRunner {

    private static final String CONSUMER = MqConst.Consumer.COMPANY_SEARCH;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CompanySearchService companySearchService;
    private final CompanyMapper companyMapper;
    private final MqConsumeLogMapper mqConsumeLogMapper;

    @Override
    public void run(ApplicationArguments args) {
        companySearchService.ensureIndex();
        List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getVerifyStatus, 1));
        companies.forEach(companySearchService::index);
        log.info("company_index 启动初始化完成，索引 {} 个已认证企业", companies.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyChanged(CompanySyncEvent event) {
        MqMessage msg = new MqMessage();
        msg.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        msg.setBizType(MqConst.BizType.COMPANY_UPSERT);
        msg.setBizId(event.getCompanyId());
        msg.setPayload("{\"companyId\":" + event.getCompanyId() + "}");
        rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_COMPANY_UPSERT, msg);
    }

    @RabbitListener(queues = MqConst.QUEUE_COMPANY_SEARCH)
    public void onSync(MqMessage message, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            Long consumed = mqConsumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                    .eq(MqConsumeLog::getMessageId, message.getMessageId())
                    .eq(MqConsumeLog::getConsumer, CONSUMER));
            if (consumed != null && consumed > 0) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            MqPayload.CompanySync payload = objectMapper.readValue(message.getPayload(), MqPayload.CompanySync.class);
            Company company = companyMapper.selectById(payload.getCompanyId());
            if (company != null) {
                companySearchService.index(company);
            }
            MqConsumeLog logRow = new MqConsumeLog();
            logRow.setMessageId(message.getMessageId());
            logRow.setConsumer(CONSUMER);
            logRow.setCreateTime(LocalDateTime.now());
            mqConsumeLogMapper.insert(logRow);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("企业 ES 同步失败，重新入队: messageId={}, err={}", message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
