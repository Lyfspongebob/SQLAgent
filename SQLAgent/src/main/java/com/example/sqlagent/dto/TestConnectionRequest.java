package com.example.sqlagent.dto;

import lombok.Data;

@Data
public class TestConnectionRequest {
    private Long id;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
}
