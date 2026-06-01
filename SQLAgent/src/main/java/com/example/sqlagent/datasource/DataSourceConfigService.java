package com.example.sqlagent.datasource;

import com.example.sqlagent.dto.DataSourceConfigRequest;
import com.example.sqlagent.dto.TestConnectionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DataSourceConfigService {

    @Autowired
    private DataSourceConfigRepository repository;

    @Autowired
    private DynamicDataSourceManager dynamicDataSourceManager;

    public List<DataSourceConfig> getConfigsByUserId(Long userId) {
        return repository.findByUserId(userId);
    }

    public DataSourceConfig getConfigById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("数据源配置不存在"));
    }

    @Transactional
    public DataSourceConfig saveConfig(Long userId, DataSourceConfigRequest request) {
        DataSourceConfig config = new DataSourceConfig();
        config.setUserId(userId);
        config.setName(request.getName());
        config.setDbType(request.getDbType());
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDatabaseName(request.getDatabaseName());
        config.setUsername(request.getUsername());
        config.setPassword(request.getPassword());
        config.setDescription(request.getDescription());
        return repository.save(config);
    }

    @Transactional
    public DataSourceConfig updateConfig(DataSourceConfigRequest request) {
        DataSourceConfig config = repository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("数据源配置不存在"));

        config.setName(request.getName());
        config.setDbType(request.getDbType());
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDatabaseName(request.getDatabaseName());
        config.setUsername(request.getUsername());
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            config.setPassword(request.getPassword());
        }
        config.setDescription(request.getDescription());
        config.setUpdateTime(LocalDateTime.now());

        // 清除缓存，下次重新创建连接
        dynamicDataSourceManager.removeDataSource(config.getId());

        return repository.save(config);
    }

    @Transactional
    public void deleteConfig(Long id) {
        dynamicDataSourceManager.removeDataSource(id);
        repository.deleteById(id);
    }

    public boolean testConnection(TestConnectionRequest request) {
        return dynamicDataSourceManager.testConnection(
                request.getDbType(),
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getUsername(),
                request.getPassword()
        );
    }
}
