package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    /**
     * 发送验证码
     * @param phone 手机号
     * @param session session
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号合法性
        if (!RegexUtils.isPhoneInvalid(phone)) {
            //不合法返回
            return Result.fail("手机号格式错误！");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码
        session.setAttribute("code", code);
        log.debug("发送验证码成功，验证码为{}", code);
        //发送验证码
        return Result.ok();
    }
}
