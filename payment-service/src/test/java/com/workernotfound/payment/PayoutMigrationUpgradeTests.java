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
    // V12 이후 마이그레이션 수와 최신 버전은 파일 목록에서 계산한다. 새 마이그레이션이 추가돼도 같은 경로로 순서대로 적용되는지 본다.
    int laterMigrations = 0, latestVersion = 12;
    for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
      int version = Integer.parseInt(resource.getFilename().split("__")[0].substring(1));
      if (version > 12) {
        laterMigrations++;
        latestVersion = Math.max(latestVersion, version);
        continue;
      }
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
        assertThat(laterMigrations).isGreaterThanOrEqualTo(2);
        assertThat(current.migrate().migrationsExecuted).isEqualTo(laterMigrations);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo(Integer.toString(latestVersion));
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
