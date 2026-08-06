package com.rentflow.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RedissonClient by hand (rather than using Redisson's Spring Boot starter)
 * so the connection details come from the same spring.data.redis.* properties everything
 * else uses, and so there's no second auto-configuration to keep compatible.
 */
@Configuration
public class RedissonConfig {

    /** destroyMethod = "shutdown" so the Netty threads Redisson owns die with the context. */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {

        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
