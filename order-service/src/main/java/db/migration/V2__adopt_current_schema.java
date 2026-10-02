package db.migration;

import org.flywaydb.core.api.migration.*;
import java.sql.*;

/** Adds missing columns/lookup indexes; refuses invalid legacy rows rather than modifying or deleting them. */
public class V2__adopt_current_schema extends BaseJavaMigration {
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        column(connection, "t_orders", "payment_method", "VARCHAR(32)");
        column(connection, "t_orders", "shipping_address_label", "VARCHAR(128)");
        column(connection, "t_orders", "shipping_recipient_name", "VARCHAR(128)");
        column(connection, "t_orders", "shipping_recipient_phone", "VARCHAR(32)");
        column(connection, "t_orders", "shipping_address_line", "VARCHAR(512)");
        column(connection, "t_orders", "cancel_reason", "VARCHAR(255)");
        column(connection, "t_orders", "cancelled_at", "DATETIME(6)");
        index(connection, "t_orders", "idx_orders_user_date", "user_id, order_date");
        check(connection, "t_orders_line_items", "ck_order_item_quantity", "quantity IS NOT NULL AND quantity > 0 AND price IS NOT NULL AND price >= 0 AND order_id IS NOT NULL AND sku_code IS NOT NULL");
    }
    private void column(Connection connection, String table, String name, String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, name)) {
            if (!columns.next()) execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition);
        }
    }
    private void index(Connection connection, String table, String name, String columns) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
        }
        execute(connection, "CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
    }
    private void check(Connection connection, String table, String name, String expression) throws SQLException {
        try (Statement statement = connection.createStatement();ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE NOT (" + expression + ")")) {
            rows.next();if (rows.getLong(1) != 0) throw new SQLException("Legacy data violates " + name + "; review and repair rows before adopting migrations");
        }
        // V2 is versioned and executes once after the explicitly selected baseline.
        execute(connection, "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " CHECK (" + expression + ")");
    }
    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
