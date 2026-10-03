package com.myexampleproject.orderservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowDeadLetters {
    private final JdbcTemplate jdbc;
    public void record(String topic, int partition, long offset, String orderNumber) {
        jdbc.update("INSERT INTO workflow_dead_letter(source_topic,source_partition,source_offset,order_number,recorded_at) SELECT ?,?,?,?,CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM workflow_dead_letter WHERE source_topic=? AND source_partition=? AND source_offset=?)",
                topic,partition,offset,orderNumber,topic,partition,offset);
    }
}
