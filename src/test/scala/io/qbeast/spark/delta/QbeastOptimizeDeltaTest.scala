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
package io.qbeast.spark.delta

import io.qbeast.core.model.IndexFile
import io.qbeast.core.model.QTableID
import io.qbeast.internal.commands.ConvertToQbeastCommand
import io.qbeast.table.QbeastTable
import io.qbeast.QbeastIntegrationTestSpec
import org.apache.spark.sql.delta.actions.Action
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.actions.CommitInfo
import org.apache.spark.sql.delta.actions.RemoveFile
import org.apache.spark.sql.delta.util.FileNames
import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession

class QbeastOptimizeDeltaTest extends QbeastIntegrationTestSpec {

  "Table optimize" should "set the dataChange flag as false" in
    withQbeastContextSparkAndTmpDir { (spark, tmpDir) =>
      import spark.implicits._

      val df = spark.sparkContext.range(0, 10).toDF("id")
      df.write
        .mode("append")
        .format("qbeast")
        .option("columnsToIndex", "id")
        .save(tmpDir)

      QbeastTable.forPath(spark, tmpDir).optimize(1L, Map.empty[String, String])

      val deltaLog = DeltaLog.forTable(spark, tmpDir)
      val snapshot = deltaLog.update()
      val conf = deltaLog.newDeltaHadoopConf()

      deltaLog.store
        .read(FileNames.deltaFile(deltaLog.logPath, snapshot.version), conf)
        .map(Action.fromJson)
        .collect({
          case addFile: AddFile => addFile.dataChange shouldBe false
          case removeFile: RemoveFile => removeFile.dataChange shouldBe false
          case commitInfo: CommitInfo =>
            commitInfo.isolationLevel shouldBe Some("SnapshotIsolation")
          case _ => None
        })

    }

  /**
   * Get the unindexed files from the last updated Snapshot
   * @param qtableID
   *   table id
   * @return
   */
  def getUnindexedFiles(qtableID: QTableID): Dataset[IndexFile] = {
    getQbeastSnapshot(qtableID.id).loadIndexFiles(0L) // Revision 0L
  }

  /**
   * Get the indexed files from the last updated Snapshot
   * @param qtableID
   *   table id
   * @return
   */
  def getIndexedFiles(qtableID: QTableID): Dataset[IndexFile] = {
    getQbeastSnapshot(qtableID.id).loadLatestIndexFiles
  }

  def getAllFiles(spark: SparkSession, d: QTableID): Dataset[AddFile] = {
    DeltaLog.forTable(spark, d.id).update().allFiles
  }

  def checkLatestRevisionAfterOptimize(spark: SparkSession, qTableID: QTableID): Unit = {
    // Check that the revision of the files is correct
    val indexedFiles = getIndexedFiles(qTableID)
    val qbeastTable = QbeastTable.forPath(spark, qTableID.id)
    qbeastTable.allRevisions().size shouldBe 2L // 2 Revisions: 0L and 1L
    qbeastTable.latestRevisionID shouldBe 1L
    qbeastTable.indexedColumns() shouldBe Seq("id")
    indexedFiles
      .select("revisionId")
      .distinct()
      .count() shouldBe 1L // 1 Revision
    indexedFiles
      .select("revisionId")
      .head()
      .getLong(0) shouldBe 1L // The latest Revision
  }

  "Optimizing the Revision 0L" should "optimize a table converted to Qbeast" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      spark
        .range(50)
        .write
        .mode("append")
        .format("delta")
        .save(tmpDir) // Append data without indexing

      ConvertToQbeastCommand(identifier = s"delta.`$tmpDir`", columnsToIndex = Seq("id"))
        .run(spark)

      val qtableID = QTableID(tmpDir)
      val firstUnindexedFiles = getUnindexedFiles(qtableID)
      val allFiles = getAllFiles(spark, qtableID)
      firstUnindexedFiles.count() shouldBe allFiles.count()
      // Optimize the Table
      val qt = QbeastTable.forPath(spark, tmpDir)
      qt.optimize(0L)

      // After optimization, all files from the Legacy Table should be indexed
      val unindexedFiles = getUnindexedFiles(qtableID)
      unindexedFiles shouldBe empty
      // Check that the indexed files are correct
      val indexedFiles = getIndexedFiles(qtableID)
      val allFilesAfter = getAllFiles(spark, qtableID)
      indexedFiles.count() shouldBe allFilesAfter.count()

      checkLatestRevisionAfterOptimize(spark, qtableID)

  }

  it should "optimize and Hybrid Table" in withQbeastContextSparkAndTmpDir { (spark, tmpDir) =>
    spark
      .range(50)
      .write
      .mode("append")
      .option("columnsToIndex", "id")
      .format("qbeast")
      .save(tmpDir)

    spark
      .range(50)
      .write
      .mode("append")
      .format("delta")
      .save(tmpDir) // Append data without indexing

    val qtableID = QTableID(tmpDir)
    val firstUnindexedFiles = getUnindexedFiles(qtableID)
    firstUnindexedFiles should not be empty

    // Optimize the Table
    val qt = QbeastTable.forPath(spark, tmpDir)
    qt.optimize(0L)

    // After optimization, all files from the Hybrid Table should be indexed
    val unindexedFiles = getUnindexedFiles(qtableID)
    unindexedFiles shouldBe empty

    // Check that the revision is correct
    checkLatestRevisionAfterOptimize(spark, qtableID)
  }

  it should "Optimize a fraction of the Staging Area" in withQbeastContextSparkAndTmpDir {
    (spark, tmpDir) =>
      // Index with Qbeast
      spark
        .range(50)
        .write
        .mode("append")
        .format("qbeast")
        .option("columnsToIndex", "id")
        .save(tmpDir)

      spark
        .range(100)
        .write
        .mode("append")
        .format("delta")
        .save(tmpDir) // Append data without indexing

      // Check that the number of unindexed files is not 0
      val qtableID = QTableID(tmpDir)
      val unindexedFilesBefore = getUnindexedFiles(qtableID)
      val unindexedFilesCount = unindexedFilesBefore.count()
      unindexedFilesCount should be > 0L
      val unindexedFilesSize = unindexedFilesBefore.collect().map(_.size).sum

      // Optimize the Table with a 0.5 fraction
      val qt = QbeastTable.forPath(spark, tmpDir)
      val fractionToOptimize = 0.5
      qt.optimize(revisionID = 0L, fraction = fractionToOptimize)

      // After optimization, half of the Staging Area should be indexed
      val unindexedFilesAfter = getUnindexedFiles(qtableID)
      // Not all files should be indexed
      unindexedFilesAfter should not be empty
      // The number of unindexed files should be less than the original number
      unindexedFilesAfter.count() shouldBe <(unindexedFilesCount)
      // The size of the unindexed files should be less or equal than the missing fraction to optimize
      val unindexedFilesSizeAfter = unindexedFilesAfter.collect().map(_.size).sum
      unindexedFilesSizeAfter shouldBe >(0L)
      unindexedFilesSizeAfter shouldBe <=(
        ((1.0 - fractionToOptimize) * unindexedFilesSize).toLong)

      // Second optimization should index the rest of the Staging Area
      qt.optimize(revisionID = 0L, fraction = 1.0)
      val unindexedFiles2 = getUnindexedFiles(qtableID)
      unindexedFiles2 shouldBe empty
  }

}
