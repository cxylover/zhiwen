package com.tongji.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RedissonConfig {
    @Value("${counter.rebuild.lock.watchdog-ms:30000}")
    private long lockWatchdogMs;
     /**
     * 创建 RedissonClient 实例。
     *
     * @param redisProperties Redis 配置属性
     * @return RedissonClient 实例
     */
    @Bean
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        // 配置 Redisson 的锁看门狗超时，用于自动续约锁
        config.setLockWatchdogTimeout(lockWatchdogMs);// 锁看门狗超时时间
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();// Redis 地址
        SingleServerConfig single = config.useSingleServer().setAddress(address);// 单节点配置

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {// 密码
            single.setPassword(redisProperties.getPassword());// 密码
        }

        // Spring Boot RedisProperties#getDatabase 返回的是原始 int（默认 0），无需判空
        single.setDatabase(redisProperties.getDatabase());// 数据库索引
        return Redisson.create(config);// 创建 RedissonClient 实例并返回
    }
}