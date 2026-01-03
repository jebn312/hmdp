package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Autowired
    private IFollowService followService;
    @Autowired
    private RedissonClient redissonClient;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("笔记不存在");
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        if(userId == null) return;
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
    /**
     * 点赞功能实现
     * @param id
     */
    @Override
    public void likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            boolean isUpdate = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isUpdate) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean isUpdate = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isUpdate) stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> userList = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(userList == null || userList.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = userList.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> result = userService.query().in("id", ids).last("order by field(id, " + idStr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(result);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2保存笔记
        blog.setUserId(userId);
        boolean isSave = save(blog);
        if(!isSave) Result.fail("保存失败");
        //3获取粉丝列表
        LambdaQueryWrapper<Follow> eq = new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId);
        List<Follow> list = followService.list(eq);
        //4推送给粉丝
        if(CollectionUtil.isNotEmpty(list)) {
            for (Follow follow : list) {
                Long id = follow.getUserId();
                String key = RedisConstants.FEED_KEY + id;
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            }
        }
        return Result.ok(blog.getId());
    }

    /**
     * 查询收件箱
     * @param lastId 最后分数
     * @param offset 偏移量
     * @return
     */
    @Override
    public Result queryBlowOfFollow(Long lastId, Integer offset) {
        //从用户的收件箱中拉
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, lastId, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()) return Result.ok();
        //得分排序
        List<Long> idList = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            idList.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            //如果time相同，offset+1,否则offset=1
            if(minTime == time) os++;
            else {
                minTime = time;
                os = 1;
            }
        }
        //获取笔记
        LambdaQueryWrapper<Blog> eq = new LambdaQueryWrapper<Blog>().in(Blog::getId, idList).last("order by field(id, " + StrUtil.join(",", idList) + ")");
        List<Blog> blogList = list(eq).stream().map(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
            return blog;}).collect(Collectors.toList());
        //返回数据和偏移量和时间戳(分数)
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }
}
