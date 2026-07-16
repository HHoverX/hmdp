package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key,Object value, Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryByPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R>dbFallBack, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            log.info("缓存命中");
            R r = JSONUtil.toBean(json,type);
            return r;
        }
        //判断命中的是否为空值(空值防止缓存穿透)
        if(json != null){
            return null;
        }
        //2.redis不存在,查数据库
        R r = dbFallBack.apply(id);
        if(r == null){
            log.info("缓存与数据库均未命中");
            //把空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        //3.写入redis
        this.set(key,r,time,unit);

        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <ID,R> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R>type,Function<ID,R>dbFallBack,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            //未命中，返回空值
            return null;
        }
        //2.缓存命中，判断是否过期
        //2.1将缓存数据反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //查询缓存是否过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return r;
        }
        //过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = this.tryLock(lockKey);
        if(isLock){
            //获取成功，开启独立线程,双重检查缓存是否过期
            if(!redisData.getExpireTime().isAfter(LocalDateTime.now())){
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    //重建缓存
                    try {
                        //查询数据库
                        R apply = dbFallBack.apply(id);
                        //写入redis
                        this.setWithLogicalExpire(key,apply,time,unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unLock(lockKey);
                    }
                });
            }
        }
        //获取失败，返回商铺信息
        return r;
    }
    public boolean tryLock(String key){
        //获取互斥锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(String key){
        //释放锁
        stringRedisTemplate.delete(key);
    }
}
