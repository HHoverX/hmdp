package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String kEY_PREFIX = "lock:";
    //相同jvm有线程号区分，不同jvm有UUID区分
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //初始化脚本文件
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取锁
        String key = kEY_PREFIX + name;
        //存入的线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key,threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unLock() {
        //调用lua脚本释放锁--一行代码保证释放锁的原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(kEY_PREFIX + name),ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unLock() {
//        //释放锁
//        String key = kEY_PREFIX + name;
//        String id = stringRedisTemplate.opsForValue().get(key);
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //判断锁是否还是原线程的锁，防止误删
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(key);
//        }
//    }
}
