package com.example.sqlagent.user;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.dto.LoginRequest;
import com.example.sqlagent.dto.LoginResponse;
import com.example.sqlagent.dto.RegisterRequest;
import com.example.sqlagent.error.AuthenticationException;
import com.example.sqlagent.error.ConflictException;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException(
                    ErrorCode.USERNAME_ALREADY_EXISTS,
                    "用户名已存在"
            );
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        return userRepository.save(user);
    }

    public LoginResponse login(LoginRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername());
        if (userOpt.isEmpty()) {
            throw new AuthenticationException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "用户名或密码错误"
            );
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AuthenticationException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "用户名或密码错误"
            );
        }

        StpUtil.login(user.getUserId());
        String token = StpUtil.getTokenValue();

        return new LoginResponse(token, user);
    }

    @Transactional
    public void logout() {
        long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
    }

    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.USER_NOT_FOUND,
                        "用户不存在"
                ));
    }
}
