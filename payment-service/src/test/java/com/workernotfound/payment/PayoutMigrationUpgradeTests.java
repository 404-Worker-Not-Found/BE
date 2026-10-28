package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.assertThat;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.nio.file.*;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class PayoutMigrationUpgradeTests extends IntegrationTestSupport {
  @TempDir Path baseline;

  @Test void appliesPayoutSchemaAfterAlreadyAppliedDevelopV12WithoutOutOfOrder() throws Exception {
    for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
      if (resource.getFilename().endsWith("__settlement_payout_commands.sql")) continue;
      try (var input = resource.getInputStream()) {
        Files.copy(input, baseline.resolve(resource.getFilename()));
      }
    }
    String schema = "payout_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection root = openRootConnection()) {
      var dataSource = new SingleConnectionDataSource(root, true);
      try {
        var old = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("filesystem:" + baseline).load();
        old.migrate();
        assertThat(old.info().current().getVersion().getVersion()).isEqualTo("12");
        var current = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("13");
        try (var statement = root.createStatement(); var result = statement.executeQuery(
            "SELECT COUNT(*) FROM `" + schema + "`.payment_payout_commands")) {
          assertThat(result.next()).isTrue();
          assertThat(result.getLong(1)).isZero();
        }
      } finally {
        try (var statement = root.createStatement()) { statement.execute("DROP DATABASE IF EXISTS `" + schema + "`"); }
      }
    }
  }
}
