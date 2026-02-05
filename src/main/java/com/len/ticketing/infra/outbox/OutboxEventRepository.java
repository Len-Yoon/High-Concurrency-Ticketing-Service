package com.len.ticketing.infra.outbox;

import com.len.ticketing.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query(value = """
        SELECT * FROM outbox_event
         WHERE status = 'PENDING'
           AND next_retry_at <= NOW()
         ORDER BY created_at
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);
}
