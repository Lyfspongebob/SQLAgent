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
        long userId = StpUtil.getLoginIdAsLong();
        List<DataSourceConfig> configs = dataSourceConfigService.getConfigsByUserId(userId);
        // 隐藏密码（返回脱敏信息，仅用于前端展示）
        configs.forEach(c -> c.setPassword("******"));
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> getById(@PathVariable Long id) {
        DataSourceConfig config = dataSourceConfigService.getConfigById(id);
        config.setPassword(null);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @PostMapping("/save")
    public ResponseEntity<ApiResponse<DataSourceConfig>> save(@RequestBody DataSourceConfigRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        DataSourceConfig config = dataSourceConfigService.saveConfig(userId, request);
        config.setPassword(null);
        return ResponseEntity.ok(ApiResponse.success("保存成功", config));
    }

    @PutMapping("/update")
    public ResponseEntity<ApiResponse<DataSourceConfig>> update(@RequestBody DataSourceConfigRequest request) {
        DataSourceConfig config = dataSourceConfigService.updateConfig(request);
        config.setPassword(null);
        return ResponseEntity.ok(ApiResponse.success("更新成功", config));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        dataSourceConfigService.deleteConfig(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<ApiResponse<Boolean>> testConnection(@RequestBody TestConnectionRequest request) {
        dataSourceConfigService.testConnection(request);
        return ResponseEntity.ok(ApiResponse.success("连接成功", true));
    }
}
