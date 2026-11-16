package io.sqlguard.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlTextTest {
    @Test
    void groupsPreparedAndInlineValuesAndVariableInLists() {
        var inline = SqlText.redact("""
                /* credential=hidden-comment */
                SELECT * FROM orders WHERE email='hidden''@example.test' AND id IN (1, 2, 3);
                """, i -> null);
        var bound = SqlText.redact("select * from orders where email = ? and id in (?)", i -> null);

        assertThat(inline.shape()).isEqualTo(bound.shape());
        assertThat(inline.sql()).doesNotContain("hidden", "example.test").contains("IN (?, ?, ?)");
        assertThat(inline.explainable()).isFalse();
        assertThat(bound.explainable()).isTrue();
    }

    @Test
    void handlesMysqlLiteralFormatsAndPaginationParameterOrder() {
        var first = SqlText.redact("SELECT 0xAABB, _utf8mb4'private', b'101', 1.25e4 FROM t WHERE id > ? LIMIT ?, ?",
                i -> i == 1 ? 3250L : 20L);
        var second = SqlText.redact("SELECT * FROM t LIMIT 20 OFFSET 5000", i -> null);

        assertThat(first.sql()).isEqualTo("SELECT ?, ?, ?, ? FROM t WHERE id > ? LIMIT ?, ?");
        assertThat(first.offset()).isEqualTo(3250);
        assertThat(second.offset()).isEqualTo(5000);
    }

    @Test
    void identifiesConstantPredicateBeforeRemovingLiterals() {
        assertThat(SqlText.redact("UPDATE flags SET seen = 1 WHERE (1 = 1)", i -> null).alwaysTrue()).isTrue();
        assertThat(SqlText.redact("DELETE FROM flags WHERE 1 = 0", i -> null).alwaysTrue()).isFalse();
        assertThat(SqlText.redact("DELETE FROM flags WHERE id = 1", i -> null).alwaysTrue()).isFalse();
    }

    @Test
    void declinesAmbiguousSqlInsteadOfExposingFragments() {
        assertThatThrownBy(() -> SqlText.redact("SELECT /*! 'private' */ 1", i -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlText.redact("SELECT 'private", i -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlText.redact("SELECT 'p\\'rivate'", i -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
