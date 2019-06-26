package no.nav.helse.spade.feedback

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.sql.*
import javax.sql.*

class DbMigrationTest {

   companion object {

      private lateinit var dataSource: DataSource

      @BeforeAll
      @JvmStatic
      fun setup() {
         Class.forName("org.hsqldb.jdbc.JDBCDriver")
         dataSource = createRegularDatasource("jdbc:hsqldb:mem:spade", "sa", "")
         dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
               stmt.execute("SET DATABASE SQL SYNTAX PGS TRUE")
            }
         }
         migrate(dataSource)
      }

   }

   @Test
   fun `migration smoke test`() {
      executeQuery("SELECT count(*) as numtables FROM information_schema.tables WHERE table_name='FEEDBACK'").use {
         it.next()
         assertEquals(1, it.getInt("numtables"))
      }
   }

   private fun executeQuery(sql: String): ResultSet {
      return dataSource.connection.use { conn ->
         conn.createStatement().use { stmt ->
            stmt.executeQuery(sql)
         }
      }
   }

}
