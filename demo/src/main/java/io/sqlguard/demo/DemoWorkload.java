package io.sqlguard.demo;

import io.sqlguard.starter.SqlGuardScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class DemoWorkload {
    private static final int ORDER_COUNT = 8000;
    private static final int USER_COUNT = 300;

    private final DemoMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private volatile boolean ready;
    private long round;

    DemoWorkload(DemoMapper mapper, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepare() {
        transaction.executeWithoutResult(status -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM guard_orders", Integer.class) > 0) {
                return;
            }
            jdbc.batchUpdate("INSERT INTO guard_users (id, name, email) VALUES (?, ?, ?)",
                    ids(USER_COUNT), 250, (ps, id) -> {
                        ps.setInt(1, id);
                        ps.setString(2, "演示用户 " + id);
                        ps.setString(3, "user" + id + "@example.test");
                    });
            Instant now = Instant.now();
            jdbc.batchUpdate("""
                    INSERT INTO guard_orders (id, user_id, region_id, status_code, total_cents, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, ids(ORDER_COUNT), 250, (ps, id) -> {
                        ps.setInt(1, id);
                        ps.setInt(2, random.nextInt(1, USER_COUNT + 1));
                        ps.setInt(3, random.nextInt(1, 13));
                        ps.setInt(4, random.nextInt(1, 5));
                        ps.setInt(5, random.nextInt(200, 200_000));
                        ps.setTimestamp(6, Timestamp.from(now.minusSeconds(id * 137L)));
                    });
            jdbc.batchUpdate("INSERT INTO guard_order_items (id, order_id, quantity) VALUES (?, ?, ?)",
                    ids(ORDER_COUNT * 2), 250, (ps, id) -> {
                        ps.setInt(1, id);
                        ps.setInt(2, (id - 1) % ORDER_COUNT + 1);
                        ps.setInt(3, random.nextInt(1, 6));
                    });
            jdbc.update("INSERT INTO guard_demo_flags (id, touched) VALUES (1, 0)");
        });
        ready = true;
    }

    @Scheduled(fixedDelayString = "${sqlguard.demo.interval-ms:1500}")
    void generateTraffic() {
        if (!ready) {
            return;
        }
        long iteration = round++;
        for (int i = 0, reads = random.nextInt(8, 22); i < reads; i++) {
            mapper.findOrder(random.nextInt(1, ORDER_COUNT + 1));
        }
        mapper.findUserByEmail("user" + random.nextInt(1, USER_COUNT + 1) + "@example.test");
        mapper.filterOrders(random.nextInt(1, 5), random.nextInt(1, 13), 25);
        mapper.updateOrder(random.nextInt(1, ORDER_COUNT + 1), random.nextInt(1, 5));
        if (iteration % 2 == 0) {
            mapper.pageOrders(25, random.nextInt(1500, 7500));
            mapper.salesByRegion(random.nextInt(1, 5));
        }
        if (iteration % 4 == 0) {
            try (SqlGuardScope ignored = SqlGuardScope.open()) {
                for (Long id : mapper.listUsers(9)) {
                    mapper.findUser(id);
                }
            }
        }
        if (iteration % 16 == 0) {
            mapper.touchDemoFlags();
        }
    }

    private static List<Integer> ids(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }
}
