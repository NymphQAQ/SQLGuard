CREATE TABLE IF NOT EXISTS guard_orders (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    region_id INT NOT NULL,
    status_code INT NOT NULL,
    total_cents INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS guard_order_items (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT fk_guard_item_order FOREIGN KEY (order_id) REFERENCES guard_orders (id)
);
CREATE TABLE IF NOT EXISTS guard_users (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(120) NOT NULL
);
CREATE TABLE IF NOT EXISTS guard_demo_flags (
    id BIGINT PRIMARY KEY,
    touched BIGINT NOT NULL
);
