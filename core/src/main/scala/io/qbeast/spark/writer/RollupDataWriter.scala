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
package io.qbeast.spark.writer

import io.qbeast.core.model._
import io.qbeast.spark.index.QbeastColumns
import io.qbeast.IISeq
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.execution.datasources.WriteJobStatsTracker
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.DataFrame
import org.apache.spark.util.SerializableConfiguration

import java.util.UUID
import scala.collection.mutable

/**
 * Implementation of DataWriter that applies rollup to compact the files.
 */
trait RollupDataWriter extends DataWriter {

  type ProcessRows = (InternalRow, String) => (InternalRow, String)
  private type GetCubeMaxWeight = CubeId => Weight
  private type Extract = InternalRow => (InternalRow, Weight, CubeId, String)
  private type WriteRows = Iterator[InternalRow] => Iterator[(IndexFile, TaskStats)]

  protected def doWrite(
      tableId: QTableID,
      schema: StructType,
      extendedData: DataFrame,
      tableChanges: TableChanges,
      trackers: Seq[WriteJobStatsTracker],
      processRow: Option[ProcessRows] = None): IISeq[(IndexFile, TaskStats)] = {

    val revision = tableChanges.updatedRevision
    val getCubeMaxWeight = { cubeId: CubeId =>
      tableChanges.cubeWeight(cubeId).getOrElse(Weight.MaxValue)
    }
    val writeRows =
      getWriteRows(
        tableId,
        schema,
        extendedData,
        revision,
        getCubeMaxWeight,
        trackers,
        processRow)
    extendedData
      .repartition(col(QbeastColumns.fileUUIDColumnName))
      .queryExecution
      .executedPlan
      .execute()
      .mapPartitions(writeRows)
      .collect()
      .toIndexedSeq
  }

  private def getWriteRows(
      tableId: QTableID,
      schema: StructType,
      extendedData: DataFrame,
      revision: Revision,
      getCubeMaxWeight: GetCubeMaxWeight,
      trackers: Seq[WriteJobStatsTracker],
      processRow: Option[ProcessRows]): WriteRows = {
    val extract = getExtract(extendedData, revision, processRow)
    val revisionId = revision.revisionID
    val writerFactory =
      getIndexFileWriterFactory(tableId, schema, extendedData, revisionId, trackers)
    extendedRows => {
      val writers = mutable.Map.empty[String, IndexFileWriter]
      extendedRows.foreach { extendedRow =>
        val (row, weight, cubeId, filename) = extract(extendedRow)
        val cubeMaxWeight = getCubeMaxWeight(cubeId)
        val writer =
          writers.getOrElseUpdate(filename, writerFactory.createIndexFileWriter(filename))
        writer.write(row, weight, cubeId, cubeMaxWeight)
      }
      writers.values.iterator.map(_.close())
    }
  }

  private def getExtract(
      extendedData: DataFrame,
      revision: Revision,
      processRow: Option[ProcessRows]): Extract = {
    val schema = extendedData.schema
    val qbeastColumns = QbeastColumns(extendedData)
    val extractors = schema.fields.indices
      .filterNot(qbeastColumns.contains)
      .map { i => row: InternalRow =>
        row.get(i, schema(i).dataType)
      }
    extendedRow => {
      val fileUUID = extendedRow.getString(qbeastColumns.fileUUIDColumnIndex)
      val row = InternalRow.fromSeq(extractors.map(_.apply(extendedRow)))
      val (processedRow, filename) = processRow match {
        case Some(func) => func(row, fileUUID)
        case None => (row, s"$fileUUID.parquet")
      }
      val weight = Weight(extendedRow.getInt(qbeastColumns.weightColumnIndex))
      val cubeIdBytes = extendedRow.getBinary(qbeastColumns.cubeColumnIndex)
      val cubeId = revision.createCubeId(cubeIdBytes)
      (processedRow, weight, cubeId, filename)
    }
  }

  private def getIndexFileWriterFactory(
      tableId: QTableID,
      schema: StructType,
      extendedData: DataFrame,
      revisionId: RevisionID,
      trackers: Seq[WriteJobStatsTracker]): IndexFileWriterFactory = {
    val session = extendedData.sparkSession
    val job = Job.getInstance(session.sparkContext.hadoopConfiguration)
    val outputFactory = new ParquetFileFormat().prepareWrite(session, job, Map.empty, schema)
    val config = new SerializableConfiguration(job.getConfiguration)
    new IndexFileWriterFactory(tableId, schema, revisionId, outputFactory, trackers, config)
  }

  protected def extendDataWithFileUUID(data: DataFrame, tableChanges: TableChanges): DataFrame = {
    val rollup = computeRollup(tableChanges)
    val cubeUUIDs = rollup.values.toSeq.distinct.map(c => c -> UUID.randomUUID().toString).toMap
    val rollupCubeUUIDs: Map[CubeId, String] = rollup.map { case (cubeId, rollupCubeId) =>
      cubeId -> cubeUUIDs(rollupCubeId)
    }
    val uuidUDF = getFileUUIDUDF(tableChanges.updatedRevision, rollupCubeUUIDs)
    data.withColumn(QbeastColumns.fileUUIDColumnName, uuidUDF(col(QbeastColumns.cubeColumnName)))
  }

  def computeRollup(tableChanges: TableChanges): Map[CubeId, CubeId] = {
    // TODO introduce desiredFileSize in Revision and parameters
    val desiredFileSize = tableChanges.updatedRevision.desiredCubeSize
    val rollup = new Rollup(desiredFileSize)
    tableChanges.inputBlockElementCounts.foreach { case (cubeId, blockSize) =>
      rollup.populate(cubeId, blockSize)
    }
    rollup.compute()
  }

  private def getFileUUIDUDF(
      revision: Revision,
      rollupCubeUUIDs: Map[CubeId, String]): UserDefinedFunction =
    udf({ cubeIdBytes: Array[Byte] =>
      val cubeId = revision.createCubeId(cubeIdBytes)
      var fileUUID = rollupCubeUUIDs.get(cubeId)
      var parentCubeId = cubeId.parent
      while (fileUUID.isEmpty) {
        parentCubeId match {
          case Some(value) =>
            fileUUID = rollupCubeUUIDs.get(value)
            parentCubeId = value.parent
          case None =>
        }
      }
      fileUUID.get
    })

}
