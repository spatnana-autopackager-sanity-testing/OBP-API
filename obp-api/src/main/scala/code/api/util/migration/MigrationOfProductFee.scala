package code.api.util.migration

import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import code.api.util.APIUtil
import code.api.util.migration.Migration.{DbFunction, saveLog}
import code.productfee.ProductFee
import net.liftweb.common.Full
import net.liftweb.mapper.{DB, Schemifier}
import net.liftweb.util.DefaultConnectionIdentifier

object MigrationOfProductFee {

  val oneDayAgo = ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1)
  val oneYearInFuture = ZonedDateTime.now(ZoneId.of("UTC")).plusYears(1)
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")

  def alterColumnProductFeeName(name: String): Boolean = {
    DbFunction.tableExists(ProductFee) match {
      case true =>
        val startDate = System.currentTimeMillis()
        val commitId: String = APIUtil.gitCommit
        var isSuccessful = false

        val executedSql =
          DbFunction.maybeWrite(true, Schemifier.infoF _) {
            APIUtil.getPropsValue("db.driver") match    {
              case Full(dbDriver) if dbDriver.contains("com.microsoft.sqlserver.jdbc.SQLServerDriver") =>
                () =>
                  """
                    |ALTER TABLE ProductFee ALTER COLUMN name varchar(100);
                    |""".stripMargin
              case _ =>
                () =>
                  """
                    |ALTER TABLE ProductFee ALTER COLUMN name type varchar(100);
                    |""".stripMargin
            }

          }

        val endDate = System.currentTimeMillis()
        val comment: String =
          s"""Executed SQL: 
             |$executedSql
             |""".stripMargin
        isSuccessful = true
        saveLog(name, commitId, isSuccessful, startDate, endDate, comment)
        isSuccessful

      case false =>
        val startDate = System.currentTimeMillis()
        val commitId: String = APIUtil.gitCommit
        val isSuccessful = false
        val endDate = System.currentTimeMillis()
        val comment: String =
          s"""${ProductFee._dbTableNameLC} table does not exist""".stripMargin
        saveLog(name, commitId, isSuccessful, startDate, endDate, comment)
        isSuccessful
    }
  }

}
