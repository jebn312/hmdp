package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 类描述
 *
 * @author cl
 * @since 2025.12.22
 */
@Slf4j
@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> fallback, Long time, TimeUnit unit) {
        //查redis-获取数据
        String key = keyPrefix + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        //非空-返回
        if(StrUtil.isNotBlank(data)) return JSONUtil.toBean(data, type);
        //非null-返回null
        if(data != null) return null;
        //查库-缓存-返回
        R r = fallback.apply(id);
        if(r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //查redis-获取数据
        String key = keyPrefix + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        //判空-返回
        if (StrUtil.isBlank(data)) return null;
        //非空-没过期-返回
        RedisData redisData = JSONUtil.toBean(data, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) return r;
        //非空-过期-重建缓存-返回
        String lock = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean isLock = tryLock(lock);
        if (isLock) {
            //双重检查
            String json = stringRedisTemplate.opsForValue().get(key);
            RedisData bean = JSONUtil.toBean(json, RedisData.class);
            if (bean.getExpireTime().isAfter(LocalDateTime.now()))
                return JSONUtil.toBean((JSONObject) bean.getData(), type);
            //异步缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicExpire(key, r1, time, unit);
                } catch (Exception e) {
                    log.error("queryWithLogicExpire: []", e);
                    throw new RuntimeException(e);
                } finally {
                    unlock(lock);
                }
            });
        }
        return r;
    }

    /**
     * 使用分布式锁缓存重建
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) throws InterruptedException {
        //使用key从redis获取数据
        String key = keyPrefix + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        //非空-返回
        if (StrUtil.isNotBlank(data)) {
            return JSONUtil.toBean(data, type);
        }
        //空-且非null(不存在)-反空
        if (data != null) return null;
        //查数据库-存redis-返回
        String lock = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean isLock = tryLock(lock);
        R r = null;
        if (!isLock) {
            Thread.sleep(50);
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
        }
        try {
            r = dbFallback.apply(id);

        } catch (Exception e) {
            log.error("queryWithMutex: ", e);
            throw new RuntimeException(e);
        } finally {
            unlock(lock);
        }
        return r;
    }

    private Boolean tryLock(String key) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
