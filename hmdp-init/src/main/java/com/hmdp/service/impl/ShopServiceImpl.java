package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = cacheClient.queryByPassThrough(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        if(shop == null){
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicalExpire(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis查询商铺
//        String cacheShop = stringRedisTemplate.opsForValue().get(key);
//        if(StrUtil.isBlank(cacheShop)){
//            //未命中，返回空值
//            return null;
//        }
//        //2.缓存命中，判断是否过期
//        //2.1将缓存数据反序列化为对象
//        RedisData redisData = JSONUtil.toBean(cacheShop, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
//            //未过期，直接返回店铺信息
//            return shop;
//        }
//        //过期，尝试获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        if(isLock){
//            //获取成功，开启独立线程,双重检查缓存是否过期
//            if(!redisData.getExpireTime().isAfter(LocalDateTime.now())){
//                CACHE_REBUILD_EXECUTOR.submit(()->{
//                    //重建缓存
//                    try {
//                        this.saveShopToRedis(id,20L);
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    } finally {
//                        //释放锁
//                        unLock(lockKey);
//                    }
//                });
//            }
//        }
//        //获取失败，返回商铺信息
//        return shop;
//    }
//    public Shop queryWithMutex(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis查询商铺
//        String cacheShop = stringRedisTemplate.opsForValue().get(key);
//        //缓存命中
//        if(StrUtil.isNotBlank(cacheShop)){
//            log.info("redis中存在商铺");
//            Shop shop = JSONUtil.toBean(cacheShop,Shop.class);
//            return shop;
//        }
//        //判断命中的是否为空值(空值防止缓存穿透)
//        if(cacheShop != null){
//            return null;
//        }
//        //2.缓存未命中,尝试获取互斥锁
//        String lockkey = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            if(!tryLock(lockkey)){
//                //获取锁失败，休眠并重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //互斥锁获取成功，根据id查询数据库
//            shop = getById(id);
//            if(shop == null){
//                log.info("redis中不存在商铺");
//                //把空值写入redis
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//                return null;
//            }
//            //3.写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL , TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放互斥锁
//            unLock(lockkey);
//        }
//        return shop;
//    }
//    public Shop queryByPassThrough(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis查询商铺
//        String cacheShop = stringRedisTemplate.opsForValue().get(key);
//        if(StrUtil.isNotBlank(cacheShop)){
//            log.info("redis中存在商铺");
//            Shop shop = JSONUtil.toBean(cacheShop,Shop.class);
//            return shop;
//        }
//        //判断命中的是否为空值(空值防止缓存穿透)
//        if(cacheShop != null){
//            return null;
//        }
//        //2.redis不存在,查数据库
//        Shop shop = getById(id);
//        if(shop == null){
//            log.info("redis中不存在商铺");
//            //把空值写入redis
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        //3.写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL , TimeUnit.MINUTES);
//
//        return shop;
//    }
//    public boolean tryLock(String key){
//        //获取互斥锁
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//    public void unLock(String key){
//        //释放锁
//        stringRedisTemplate.delete(key);
//    }
//    public void saveShopToRedis(Long id,Long expireSecond){
//        //查询数据库
//        Shop shop = getById(id);
//        //封装过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
//        //写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
//    }
    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.修改数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
