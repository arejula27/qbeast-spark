/*
 * Copyright 2021 Qbeast Analytics, S.L.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.qbeast.spark.utils

import io.qbeast.spark.index.DefaultFileIndex
import io.qbeast.QbeastIntegrationTestSpec
import io.qbeast.TestUtils._
import org.apache.spark.sql.execution.FileSourceScanExec
import org.apache.spark.sql.functions.avg
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.functions.rand
import org.apache.spark.sql.functions.regexp_replace
import org.apache.spark.sql.functions.when

class QbeastFilterPushdownTest extends QbeastIntegrationTestSpec {

  private val filter_user_greaterThanOrEq = "(user_id >= 536764969)"
  private val filter_user_lessThan = "(user_id < 546280860)"
  private val filter_product_lessThan = "(product_id >= 11522682)"
  private val filter_product_greaterThanOrEq = "(product_id < 50500010)"
  private val filter_user_equal = "(user_id = 536764969)"
  private val filter_product_equal = "(product_id = 11522682)"

  "Qbeast" should
    "return a valid filtering of the original dataset " +
    "for one column" in withSparkAndTmpDir { (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val filters = Seq(filter_user_lessThan, filter_user_greaterThanOrEq)
        val filter = filters.mkString(" and ")
        val qbeastQuery = df.filter(filter)
        val normalQuery = data.filter(filter)

        checkFiltersArePushedDown(qbeastQuery)
        qbeastQuery.count() shouldBe normalQuery.count()
        assertLargeDatasetEquality(qbeastQuery, normalQuery, orderedComparison = false)

      }
    }

  it should
    "return a valid filtering of the original dataset " +
    "for all columns indexed" in withSparkAndTmpDir { (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val filters = Seq(
          filter_user_lessThan,
          filter_user_greaterThanOrEq,
          filter_product_lessThan,
          filter_product_greaterThanOrEq)
        val filter = filters.mkString(" and ")
        val qbeastQuery = df.filter(filter)
        val normalQuery = data.filter(filter)

        checkFiltersArePushedDown(qbeastQuery)
        qbeastQuery.count() shouldBe normalQuery.count()
        assertLargeDatasetEquality(qbeastQuery, normalQuery, orderedComparison = false)

      }
    }

  it should
    "return a valid filtering of the original dataset " +
    "for all string columns" in withSparkAndTmpDir { (spark, tmpDir) =>
      {
        val data = loadTestData(spark).na.drop()

        writeTestData(data, Seq("brand", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)
        val filter_brand = "(`brand` == 'versace')"

        val filters = Seq(filter_user_lessThan, filter_user_greaterThanOrEq, filter_brand)
        val filter = filters.mkString(" and ")
        val qbeastQuery = df.filter(filter)
        val normalQuery = data.filter(filter)

        checkFiltersArePushedDown(qbeastQuery)
        qbeastQuery.count() shouldBe normalQuery.count()
        assertLargeDatasetEquality(qbeastQuery, normalQuery, orderedComparison = false)

      }
    }

  it should
    "return a valid filtering of the original dataset " +
    "for range string columns without using Qbeast" in withSparkAndTmpDir { (spark, tmpDir) =>
      {
        val data = loadTestData(spark).na.drop()

        writeTestData(data, Seq("brand", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)
        val filter_brand = "(`brand` > 'versace')"

        val filters = Seq(filter_user_lessThan, filter_user_greaterThanOrEq, filter_brand)
        val filter = filters.mkString(" and ")
        val qbeastQuery = df.filter(filter)
        val normalQuery = data.filter(filter)

        // The file filtering should not be applied in this particular case
        checkFiltersArePushedDown(qbeastQuery)
        assertLargeDatasetEquality(qbeastQuery, normalQuery, orderedComparison = false)

      }
    }

  it should
    "return a valid filtering of the original dataset " +
    "for null value columns" in withSparkAndTmpDir { (spark, tmpDir) =>
      {
        val data = loadTestData(spark)
        val dataWithNulls =
          data.withColumn(
            "null_product_id",
            when(rand() > 0.5, null).otherwise(col("product_id")))

        writeTestData(dataWithNulls, Seq("brand", "null_product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)
        val filter = "(`null_product_id` is null)"

        val qbeastQuery = df.filter(filter)
        val normalQuery = dataWithNulls.filter(filter)

        checkFiltersArePushedDown(qbeastQuery)
        qbeastQuery.count() shouldBe normalQuery.count()
        assertLargeDatasetEquality(qbeastQuery, normalQuery, orderedComparison = false)

      }
    }

  "Logical Optimization" should
    "pushdown the filters to the datasource" in withQbeastContextSparkAndTmpDir {
      (spark, tmpDir) =>
        {
          val data = loadTestData(spark)

          writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

          val df = spark.read.format("qbeast").load(tmpDir)

          val filters = Seq(
            filter_user_lessThan,
            filter_user_greaterThanOrEq,
            filter_product_lessThan,
            filter_product_greaterThanOrEq)
          val filter = filters.mkString(" and ")
          val query = df.selectExpr("*").sample(0.1).filter(filter)
          checkLogicalFilterPushdown(filters, query)

        }

    }

  it should "pushdown filters in complex queries" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)
        val joinData = data.withColumn("price", col("price") * 3)

        val filters = Seq(
          filter_user_lessThan,
          filter_user_greaterThanOrEq,
          filter_product_lessThan,
          filter_product_greaterThanOrEq)
        val filter = filters.mkString(" and ")
        val query = df.union(joinData).filter(filter).agg(avg("price"))

        checkLogicalFilterPushdown(filters, query)

      }

  }

  it should "pushdown filters with OR operator" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val filter = s"($filter_user_equal OR $filter_product_equal)"
        val query = df.filter(filter)
        val originalQuery = data.filter(filter)

        // OR filters are not split, so we need to match them entirely
        checkLogicalFilterPushdown(Seq(filter), query)
        checkFiltersArePushedDown(query)
        assertSmallDatasetEquality(query, originalQuery, orderedComparison = false)

      }
  }

  it should "pushdown filters with OR and AND operator" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val filter =
          s"(($filter_user_lessThan AND $filter_user_greaterThanOrEq) " +
            s"OR ($filter_product_greaterThanOrEq AND $filter_product_lessThan))"
        val query = df.filter(filter)
        val originalQuery = data.filter(filter)

        // OR filters are not split, so we need to match them entirely
        checkLogicalFilterPushdown(Seq(filter), query)
        checkFiltersArePushedDown(query)
        assertSmallDatasetEquality(query, originalQuery, orderedComparison = false)

      }
  }

  it should "pushdown filters with IN predicate" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      {
        val data = loadTestData(spark)

        writeTestData(data, Seq("user_id", "product_id"), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val filter =
          "(user_id IN (555304906, 514439763))"
        val query = df.filter(filter)
        val originalQuery = data.filter(filter)

        // OR filters are not split, so we need to match them entirely
        checkLogicalFilterPushdown(Seq(filter), query)
        checkFiltersArePushedDown(query)
        assertSmallDatasetEquality(query, originalQuery, orderedComparison = false)

      }
  }

  it should "NOT pushdown regex expressions on strings" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      {
        val data = loadTestData(spark)
        val columnName = "brand_mod"
        val modifiedData =
          data
            .withColumn(columnName, regexp_replace(col("brand"), "kipardo", "prefix_kipardo"))

        // Index data with the new column
        writeTestData(modifiedData, Seq(columnName), 10000, tmpDir)

        val df = spark.read.format("qbeast").load(tmpDir)

        val regexExpression = "prefix_(.+)"
        val filter =
          s"(regexp_extract($columnName, '$regexExpression', 1) = 'kipardo')"
        val query = df.filter(filter)
        val originalQuery = modifiedData.filter(filter)
        val originalQueryWithoutRegex = data.filter("brand = 'kipardo'")

        // OR filters are not split, so we need to match them entirely
        checkLogicalFilterPushdown(Seq(filter), query)

        // Check if the files returned from the query are equal as the files existing in the folder
        query.queryExecution.executedPlan
          .collectLeaves()
          .filter(_.isInstanceOf[FileSourceScanExec])
          .foreach {
            case f: FileSourceScanExec if f.relation.location.isInstanceOf[DefaultFileIndex] =>
              val index = f.relation.location
              val matchingFiles =
                index.listFiles(f.partitionFilters, f.dataFilters).flatMap(_.files)
              val allFiles = index.inputFiles
              matchingFiles.length shouldBe allFiles.length
          }

        assertSmallDatasetEquality(
          query,
          originalQuery,
          orderedComparison = false,
          ignoreNullable = true)
        assertSmallDatasetEquality(
          query.drop(columnName),
          originalQueryWithoutRegex,
          orderedComparison = false,
          ignoreNullable = true)

      }
  }

  it should "pushdown min-max filters on strings" in withSparkAndTmpDir((spark, tmpDir) => {
    val data = loadTestData(spark)
    writeTestData(data, Seq("brand"), 1000, tmpDir)

    val df = spark.read.format("qbeast").load(tmpDir)

    val filter = "(brand == 'versace')"
    val query = df.filter(filter)
    val originalQuery = data.filter(filter)

    // Check filter pushdown for string search
    checkFiltersArePushedDown(query)
    query.queryExecution.executedPlan
      .collectLeaves()
      .filter(_.isInstanceOf[FileSourceScanExec])
      .collectFirst {
        case f: FileSourceScanExec if f.relation.location.isInstanceOf[DefaultFileIndex] =>
          f.dataFilters.foreach(println)
          val index = f.relation.location
          val matchingFiles =
            index.listFiles(f.partitionFilters, f.dataFilters).flatMap(_.files)
          val allFiles = index.inputFiles
          matchingFiles.length shouldBe <(allFiles.length)
      }

    // Assert the results are the same
    assertSmallDatasetEquality(query, originalQuery, orderedComparison = false)
  })

}
