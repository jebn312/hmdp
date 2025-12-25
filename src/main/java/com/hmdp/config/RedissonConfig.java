package com.hmdp.config;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类
 *
 * @author cl
 * @since 2025.12.25
 */

@Data
@Configuration
@ConfigurationProperties("spring.redis")
public class RedissonConfig {

    private String host;
    private String port;
    private String password;
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer().setAddress("redis://" + host + ":" + port);
        if(StrUtil.isNotBlank(password)) singleServerConfig.setPassword(password);

        return Redisson.create(config);
    }
}
