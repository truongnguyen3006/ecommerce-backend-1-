package com.myexampleproject.paymentservice;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class MigrationTests {
    @Test void additiveMigrationsCoverFreshSchemaAndCanBeRepeated() throws Exception {
        String url="jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        Configuration configuration=new Configuration();
        configuration.setProperty("hibernate.connection.url",url);
        configuration.setProperty("hibernate.connection.username","sa");
        configuration.setProperty("hibernate.connection.password","");
        configuration.setProperty("hibernate.hbm2ddl.auto","validate");
        configuration.setProperty("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        configuration.addAnnotatedClass(com.myexampleproject.paymentservice.model.PaymentTransaction.class);
        try (var factory=configuration.buildSessionFactory()) {assertThat(factory.isOpen()).isTrue();}
        try(Connection c=DriverManager.getConnection(url,"sa","");ResultSet tables=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})) {assertThat(tables.next()).isTrue();}
    }
}
