package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.CODE;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不合法");
        }
        // 如果符合生成6位验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到redis
        String key = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码
        log.debug("发送短信验证码成功，验证码: " + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不合法");
        }
        // 区分是密码还是验证码
        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        // 验证码登录
        if (StringUtils.isEmpty(password)) {
            //从redis中获取验证码
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            // code是否正确
            if (StringUtils.isEmpty(cacheCode) || !cacheCode.equals(code)) {
                return Result.fail("验证码错误");
            }
        }
        // 查询数据库用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null) {
            // 没注册过自动注册
            user = createUserWithPhone(phone);
        }

        // 保存用户信息到redis中
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 将Java对象转换成map存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                // 忽略bean对象中的空值
                        setIgnoreNullValue(true).
                // 将bean中所有属性都转化成string类型
                        setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        // 设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.SECONDS);
        // 返回token
        return Result.ok(token);
    }


    // 用户签到功能
    @Override
    public Result sign() {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String formatDate = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        // 用户id和当前id组合成key，例如id为1010，当前日期为2024/9，组合成1010:2024:09
        String key = USER_SIGN_KEY + userId + formatDate;
        // 计算当前日期距离月初有几天
        int dayOfMonth = now.getDayOfMonth();
        // 存入bitmap中
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    // 计算用户当月连续签到的天数
    // 计算规则是从bitmap取出的数据，从当前天数算起，从后往前算起直到遇到0也就是未签到的天数为止
    // 例如0110011100111 从后往前数就是3天
    @Override
    public Result signCount() {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String formatDate = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        // 用户id和当前id组合成key，例如id为1010，当前日期为2024/9，组合成1010:2024:09
        String key = USER_SIGN_KEY + userId + formatDate;
        // 计算当前日期距离月初有几天
        int dayOfMonth = now.getDayOfMonth();
        // 取出bitmap中存储的数据 BITFIELD key GET u[dayOfMonth] 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        // 取num和1进行与运算，每次得到的就是最后一位是否签到
        // 是1则代表签到，是0则代表没有签到
        int count = 0;
        while ((num & 1) == 1){
            count++;
            // 无符号右移一位，每次都能得到最后一位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return null;
    }
}
