package com.example.sqlagent.datasource;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface DataSourceConfigRepository extends CrudRepository<DataSourceConfig, Long> {
    List<DataSourceConfig> findByUserId(Long userId);

    //数据源所有者查询，防止用户通过别人数据源ID执行SQL
    Optional<DataSourceConfig> findByIdAndUserId(Long id, Long userId);
}
