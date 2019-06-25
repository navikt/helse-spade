package no.nav.helse.spade.feedback

import com.zaxxer.hikari.*
import no.nav.vault.jdbc.hikaricp.*
import org.flywaydb.core.*
import javax.sql.*

data class DatabaseConfig(
   val admin: Boolean = false,
   val useVault: Boolean = true,
   val vaultMountpath: String? = null,
   val dbUrl: String? = null,
   val dbUsername: String? = null,
   val dbPassword: String? = null
)

fun migrate(dataSource: DataSource) {
   Flyway.configure().dataSource(dataSource).locations("db/migrations").load().migrate()
}

fun createDatasource(dbConfig: DatabaseConfig) : DataSource {
   val role = "spade-${if (dbConfig.admin) "admin" else "user"}"

   val hikariConfig = HikariConfig().apply {
      jdbcUrl = dbConfig.dbUrl
      minimumIdle = 0
      maxLifetime = 30001
      maximumPoolSize = 2
      connectionTimeout = 250
      idleTimeout = 10001

      if (dbConfig.useVault) {
         connectionInitSql = "set role $role"
      } else {
         username = dbConfig.dbUsername
         password = dbConfig.dbPassword
      }
   }

   return if (dbConfig.useVault) {
      HikariCPVaultUtil.createHikariDataSourceWithVaultIntegration(hikariConfig, dbConfig.vaultMountpath, role)
   } else {
      HikariDataSource(hikariConfig)
   }
}


