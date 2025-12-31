package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if(!isFollow) {
            remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id));
            stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_SET_KEY + userId, id.toString());
        } else {
            //持久化到数据库
            Follow f = new Follow();
            f.setUserId(userId);
            f.setFollowUserId(id);
            save(f);
            //采用set将用户id缓存到redis中
            stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_SET_KEY + userId, id.toString());

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> eq = new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id);
        Follow follow = getOne(eq);
        return Result.ok(follow != null);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //在redis中获取双方的关注列表取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_SET_KEY + userId, RedisConstants.FOLLOW_SET_KEY + id);
        //空-返回空集合
        if(intersect == null || intersect.isEmpty()) return Result.ok();
        //根据id列表去查用户信息返回
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids).stream().map(i -> BeanUtil.copyProperties(i, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

}
