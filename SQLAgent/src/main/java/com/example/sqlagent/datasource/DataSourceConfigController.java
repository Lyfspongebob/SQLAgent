package com.example.sqlagent.datasource;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.dto.DataSourceConfigRequest;
import com.example.sqlagent.dto.TestConnectionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasource")
public class DataSourceConfigController {

    @Autowired
    private DataSourceConfigService dataSourceConfigService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<DataSourceConfig>>> list() {
        try {
            long userId = StpUtil.getLoginIdAsLong();
            List<DataSourceConfig> configs = dataSourceConfigService.getConfigsByUserId(userId);
            // 隐藏密码
            configs.forEach(c -> c.setPassword(null));
            return ResponseEntity.ok(ApiResponse.success(configs));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> getById(@PathVariable Long id) {
        try {
            DataSourceConfig config = dataSourceConfigService.getConfigById(id);
            config.setPassword(null);
            return ResponseEntity.ok(ApiResponse.success(config));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/save")
    public ResponseEntity<ApiResponse<DataSourceConfig>> save(@RequestBody DataSourceConfigRequest request) {
        try {
            long userId = StpUtil.getLoginIdAsLong();
            DataSourceConfig config = dataSourceConfigService.saveConfig(userId, request);
            config.setPassword(null);
            return ResponseEntity.ok(ApiResponse.success("保存成功", config));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/update")
    public ResponseEntity<ApiResponse<DataSourceConfig>> update(@RequestBody DataSourceConfigRequest request) {
        try {
            DataSourceConfig config = dataSourceConfigService.updateConfig(request);
            config.setPassword(null);
            return ResponseEntity.ok(ApiResponse.success("更新成功", config));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            dataSourceConfigService.deleteConfig(id);
            return ResponseEntity.ok(ApiResponse.success("删除成功", null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/test-connection")
    public ResponseEntity<ApiResponse<Boolean>> testConnection(@RequestBody TestConnectionRequest request) {
        try {
            boolean success = dataSourceConfigService.testConnection(request);
            if (success) {
                return ResponseEntity.ok(ApiResponse.success("连接成功", true));
            } else {
                return ResponseEntity.ok(ApiResponse.error("连接失败，请检查配置"));
            }
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
