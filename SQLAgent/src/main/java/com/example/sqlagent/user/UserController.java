package com.example.sqlagent.user;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.dto.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<User>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        User user = userService.register(request);
        user.setPassword(null);
        return ResponseEntity.ok(ApiResponse.success("注册成功", user));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResponse loginResponse = userService.login(request);
        loginResponse.getUser().setPassword(null);
        return ResponseEntity.ok(ApiResponse.success("登录成功", loginResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        userService.logout();
        return ResponseEntity.ok(ApiResponse.success("登出成功", null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> getCurrentUser() {
        long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getUserById(userId);
        user.setPassword(null);
        return ResponseEntity.ok(ApiResponse.success(user));
    }
}
