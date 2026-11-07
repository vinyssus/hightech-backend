package db.migration;

import cm.hitechdz.auth.util.SlugUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reprend les sous-catégories existantes (simples noms de category_subs) dans la table subcategories,
 * avec un slug calculé comme pour les catégories, puis supprime l'ancienne table.
 */
public class V6__Migrate_category_subs extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        Map<Long, Set<String>> seen = new HashMap<>();
        Map<Long, Integer> positions = new HashMap<>();
        Timestamp now = Timestamp.from(Instant.now());
        try (Statement select = c.createStatement();
             ResultSet rs = select.executeQuery(
                     "SELECT category_id, name FROM category_subs ORDER BY category_id, position");
             PreparedStatement insert = c.prepareStatement(
                     "INSERT INTO subcategories (category_id, slug, name, position, created_at) VALUES (?, ?, ?, ?, ?)")) {
            while (rs.next()) {
                long categoryId = rs.getLong(1);
                String name = rs.getString(2) == null ? "" : rs.getString(2).trim();
                if (name.length() > 80) {
                    name = name.substring(0, 80);
                }
                String slug = SlugUtil.slugify(name);
                if (slug.isEmpty() || !seen.computeIfAbsent(categoryId, k -> new HashSet<>()).add(slug)) {
                    continue;   // nom vide ou doublon dans la même catégorie
                }
                int position = positions.merge(categoryId, 1, Integer::sum) - 1;
                insert.setLong(1, categoryId);
                insert.setString(2, slug);
                insert.setString(3, name);
                insert.setInt(4, position);
                insert.setTimestamp(5, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (Statement drop = c.createStatement()) {
            drop.execute("DROP TABLE category_subs");
        }
    }
}
