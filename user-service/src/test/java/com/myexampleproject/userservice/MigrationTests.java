package com.myexampleproject.userservice;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class MigrationTests {
    @Test void legacyDuplicateDefaultsKeepLowestIdWithoutChangingAddressText() throws Exception {
        String url="jdbc:h2:mem:legacy_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("2").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {q.executeUpdate("INSERT INTO t_user_address(user_keycloak_id,recipient_name,recipient_phone,address_line,is_default) VALUES('A','one','1','keep one',true),('A','two','2','keep two',true),('B','three','3','keep three',true)");}
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement();var rows=q.executeQuery("SELECT address_line,is_default FROM t_user_address ORDER BY id")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("keep one");assertThat(rows.getBoolean(2)).isTrue();
            assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("keep two");assertThat(rows.getBoolean(2)).isFalse();
            assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(2)).isTrue();
        }
    }
    @Test void additiveMigrationsCoverFreshSchemaAndCanBeRepeated() throws Exception {
        String url="jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        Configuration configuration=new Configuration();
        configuration.setProperty("hibernate.connection.url",url);
        configuration.setProperty("hibernate.connection.username","sa");
        configuration.setProperty("hibernate.connection.password","");
        configuration.setProperty("hibernate.hbm2ddl.auto","validate");
        configuration.setProperty("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        configuration.addAnnotatedClass(com.myexampleproject.userservice.model.User.class);
        configuration.addAnnotatedClass(com.myexampleproject.userservice.model.UserAddress.class);
        try (var factory=configuration.buildSessionFactory()) {assertThat(factory.isOpen()).isTrue();}
        try(Connection c=DriverManager.getConnection(url,"sa","");ResultSet tables=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})) {assertThat(tables.next()).isTrue();}
    }
}
