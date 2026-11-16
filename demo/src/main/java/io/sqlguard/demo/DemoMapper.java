package io.sqlguard.demo;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface DemoMapper {
    @Select("SELECT id, user_id, total_cents FROM guard_orders WHERE id = #{id}")
    Map<String, Object> findOrder(@Param("id") long id);

    @Select("SELECT id, name FROM guard_users WHERE id = #{id}")
    Map<String, Object> findUser(@Param("id") long id);

    @Select("SELECT id FROM guard_users ORDER BY id LIMIT #{limit}")
    List<Long> listUsers(@Param("limit") int limit);

    @Select("SELECT id, name FROM guard_users WHERE email = #{email}")
    Map<String, Object> findUserByEmail(@Param("email") String email);

    @Select("""
            SELECT id, total_cents FROM guard_orders
            WHERE status_code = #{status} AND region_id = #{region}
            ORDER BY total_cents DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> filterOrders(@Param("status") int status, @Param("region") int region,
                                           @Param("limit") int limit);

    @Select("""
            SELECT id, user_id, total_cents FROM guard_orders
            ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> pageOrders(@Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT o.region_id, SUM(i.quantity * o.total_cents) AS sales
            FROM guard_orders o JOIN guard_order_items i ON i.order_id = o.id
            WHERE o.status_code = #{status}
            GROUP BY o.region_id ORDER BY sales DESC
            """)
    List<Map<String, Object>> salesByRegion(@Param("status") int status);

    @Update("UPDATE guard_orders SET status_code = #{status} WHERE id = #{id}")
    int updateOrder(@Param("id") long id, @Param("status") int status);

    @Update("UPDATE guard_demo_flags SET touched = touched + 1")
    int touchDemoFlags();
}
