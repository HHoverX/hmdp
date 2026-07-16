package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现�? * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBySort() {
        String key = "cache: shopType";
        //1.从redis查询
        String jsonShopType = stringRedisTemplate.opsForValue().get(key);
        if(jsonShopType != null){
            log.info("redis中存在商铺类型数据");
            List<ShopType> shopTypes = JSONUtil.toList(jsonShopType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //2.redis不存在
        log.info("redis中不存在");
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //3.存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypes));

        return Result.ok(shopTypes);
    }
}
