package com.example.helloworld;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;

@RestController
@RequestMapping("/db")
public class DBController {

    private final DataSource dataSource;

    public DBController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> dbStatus() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return ResponseEntity.ok(Map.of(
                "connected", true,
                "type",      meta.getDatabaseProductName(),
                "version",   meta.getDatabaseProductVersion(),
                "url",       meta.getURL()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "connected", false,
                "error",     e.getMessage()
            ));
        }
    }
}
