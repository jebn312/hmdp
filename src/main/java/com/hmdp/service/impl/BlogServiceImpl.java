package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("笔记不存在");
        queryBlogById(blog);
        return Result.ok(blog);
    }

    private void queryBlogById(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        blog.setIsLike(stringRedisTemplate.opsForSet().isMember(key, userId.toString()));
    }
    /**
     * 点赞功能实现
     * @param id
     */
    @Override
    public void likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Boolean flag = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(flag)) {
            boolean isUpdate = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isUpdate) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            boolean isUpdate = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isUpdate) stringRedisTemplate.opsForSet().remove(key, userId.toString());
        }
    }
}
