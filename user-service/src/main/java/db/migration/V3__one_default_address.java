package db.migration;
import org.flywaydb.core.api.migration.*;
import java.sql.*;
public class V3__one_default_address extends BaseJavaMigration {
    public void migrate(Context context) throws Exception {
        var c=context.getConnection();
        // Review policy: keep lowest ID default, never remove or rewrite the owner's address text.
        try(var q=c.createStatement();var owners=q.executeQuery("SELECT user_keycloak_id, MIN(id) FROM t_user_address WHERE is_default=true GROUP BY user_keycloak_id HAVING COUNT(*)>1")) {
            try(var update=c.prepareStatement("UPDATE t_user_address SET is_default=false WHERE user_keycloak_id=? AND id<>? AND is_default=true")) {
                while(owners.next()) {update.setString(1,owners.getString(1));update.setLong(2,owners.getLong(2));update.executeUpdate();}
            }
        }
        String stored=c.getMetaData().getDatabaseProductName().equals("MySQL") ? " STORED" : "";
        try(var s=c.createStatement()) {
            s.execute("ALTER TABLE t_user_address ADD COLUMN default_owner_key VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN is_default THEN user_keycloak_id ELSE NULL END)"+stored);
            s.execute("CREATE UNIQUE INDEX uq_address_default_owner ON t_user_address(default_owner_key)");
        }
    }
}
