package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
@Api(tags = "关注模块")
public class FollowController {

    @Resource
    private IFollowService followService;

    @ApiOperation("关注/取消关注")
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable Boolean isFollow) {
        return followService.follow(id, isFollow);
    }

    @ApiOperation("查询是否关注")
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable Long id) {
        return followService.isFollow(id);
    }

    @ApiOperation("获取共同关注列表")
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
       return followService.followCommons(id);
    }
}
