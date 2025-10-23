package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询商户类型
     * @return
     */
    @Override
    public List<ShopType> queryTypeList() {
        String key = "cache:shopType";
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            List<ShopType> list = JSONUtil.toList(json, ShopType.class);
            return list;
        }
        List<ShopType> list = query().orderByAsc("sort").list();
        String jsonStr = JSONUtil.toJsonStr(list);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
        return list;
    }
}
