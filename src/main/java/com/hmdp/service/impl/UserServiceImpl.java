package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        //session.setAttribute("code", code);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, 2, TimeUnit.MINUTES);
        log.debug("发送验证码成功，验证码为{}", code);
        //发送验证码
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session session
     * @return 登录结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //验证手机号
        if(!RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号格式错误");
        //判断验证码
        //Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        String codeCache = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(code == null || !code.equals(codeCache)) return Result.fail("验证码错误");
        //判断账户是否存在
        User user = query().eq("phone", phone).one();
        if(user == null) user = createUserWithPhone(phone);
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //生成token
        String token = IdUtil.fastSimpleUUID();
        //将用户信息存入redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().ignoreNullValue().setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    /**
     * 签到功能
     * @return
     */
    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + suffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //存入redis  SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计签到功能
     * @return
     */
    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期-拼接key
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + suffix;
        //获取当前天数-获取签到列表 bitfield key get uxx 0
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        //返回连续签到天数
        if(result == null || result.isEmpty()) return Result.ok(0);
        Long l = result.get(0);
        if(l == null || l == 0) return Result.ok(0);
        //遍历-获取最后一位-0返回-1继续
        int count = 0;
        while(true) {
            if((l & 1) == 0) break;
            else count++;
            l >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
