package com.example.sqlagent.datasource;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface DataSourceConfigRepository extends CrudRepository<DataSourceConfig, Long> {
    List<DataSourceConfig> findByUserId(Long userId);
}
