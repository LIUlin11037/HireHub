package com.ll.hirehub.notification.config;

import com.ll.hirehub.notification.ws.WsPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 订阅装配（见 D-34）。
 * <p>
 * 每个实例都订阅同一个 channel {@link WsPushService#CHANNEL}；
 * 谁持有目标用户的连接，谁就下发——实例之间不需要互相知道对方存在。
 */
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final WsPushService wsPushService;

    @Bean(destroyMethod = "destroy")
    public RedisMessageListenerContainer wsRedisListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(wsPushService, new ChannelTopic(WsPushService.CHANNEL));
        return container;
    }
}
