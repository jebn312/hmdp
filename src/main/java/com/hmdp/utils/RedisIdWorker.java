package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一id生成器
 *
 * @author cl
 * @since 2025.12.24
 */

@Component
public class RedisIdWorker {

    //开始的时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号的位数
    private static final long COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        Long countObj = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        long count = countObj != null ? countObj : 1L;
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
