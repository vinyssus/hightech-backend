package cm.hitechdz.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reprise des anciennes sous-catégories (noms dans category_subs) par les migrations V5/V6. */
class SubcategoryMigrationTest {

    private static final String URL = "jdbc:h2:mem:submigration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void existing_sub_names_become_subcategories() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").target("4").load().migrate();
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("INSERT INTO categories (id, slug, name, icon, position, created_at) VALUES "
                    + "(10, 'ordinateurs', 'Ordinateurs', 'laptop', 0, CURRENT_TIMESTAMP),"
                    + "(11, 'audio', 'Audio', 'headphones', 1, CURRENT_TIMESTAMP)");
            st.execute("INSERT INTO category_subs (category_id, position, name) VALUES "
                    + "(10, 0, 'Portables'), (10, 1, 'Écrans & Moniteurs'), (10, 2, 'portables'), (10, 3, '***'),"
                    + "(11, 0, 'Écouteurs')");
        }

        Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT category_id, slug, name, position FROM subcategories ORDER BY category_id, position")) {
                while (rs.next()) {
                    rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getInt(4));
                }
            }
            assertThat(rows).containsExactly(
                    "10|portables|Portables|0",
                    "10|ecrans-moniteurs|Écrans & Moniteurs|1",
                    "11|ecouteurs|Écouteurs|0");
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM category_subs")).isInstanceOf(Exception.class);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM products WHERE subcategory_id IS NOT NULL")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }
}
