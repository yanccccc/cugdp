package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.controller.UserController;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Author:CodeCan
 * Time:2024/8/31
 */
// 获取100个用户的token
@SpringBootTest
@RunWith(SpringRunner.class)
public class GetTokens {
    @Resource
    private UserController userController;

    @Resource
    private IUserService userService;

    @Test
    public void test() {
        List<User> list = userService.query().list();
        // 指定文件名
        String fileName = "token.txt";
        try (FileWriter fw = new FileWriter(fileName);
             PrintWriter pw = new PrintWriter(fw)) {

            for (User user : list) {
                LoginFormDTO userDTO = new LoginFormDTO();
                BeanUtil.copyProperties(user, userDTO);
                HttpSession session = new MockHttpSession();
                Result login = userService.login(userDTO, session);
                // 将tokens写入到txt中
                pw.println(login.getData());
            }
        } catch (IOException e) {
            // 处理可能的异常
            e.printStackTrace();
        }


    }
}
