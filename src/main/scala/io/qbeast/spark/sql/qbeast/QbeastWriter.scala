/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.sql.qbeast

import io.qbeast.spark.index.QbeastColumns.{cubeColumnName, stateColumnName}
import io.qbeast.spark.index.{CubeId, OTreeAlgorithm, QbeastColumns, Weight}
import io.qbeast.spark.model.SpaceRevision
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.sql.delta.actions.{Action, AddFile, FileAction}
import org.apache.spark.sql.delta.commands.DeltaCommand
import org.apache.spark.sql.delta.schema.ImplicitMetadataOperation
import org.apache.spark.sql.delta.{DeltaLog, DeltaOptions, OptimisticTransaction}
import org.apache.spark.sql.execution.datasources.OutputWriterFactory
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{Metadata, MetadataBuilder}
import org.apache.spark.sql.{AnalysisExceptionFactory, DataFrame, SaveMode, SparkSession}
import org.apache.spark.util.SerializableConfiguration

/**
 * QbeastWriter is in charge of writing data to a table
 * and report the necessary log information
 * @param mode SaveMode of the write
 * @param deltaLog deltaLog associated to the table
 * @param options options for write operation
 * @param partitionColumns partition columns
 * @param data data to write
 * @param columnsToIndex qbeast columns to index
 * @param qbeastSnapshot current qbeast snapshot of the table
 * @param announcedSet set of cubes announced
 * @param oTreeAlgorithm algorithm to organize data
 */
case class QbeastWriter(
    mode: SaveMode,
    deltaLog: DeltaLog,
    options: DeltaOptions,
    partitionColumns: Seq[String],
    data: DataFrame,
    columnsToIndex: Seq[String],
    qbeastSnapshot: QbeastSnapshot,
    announcedSet: Set[CubeId],
    oTreeAlgorithm: OTreeAlgorithm)
    extends ImplicitMetadataOperation
    with DeltaCommand {

  private def isOverwriteOperation: Boolean = mode == SaveMode.Overwrite

  /**
   * Writes data to the table
   * @param txn transaction to commit
   * @param sparkSession active SparkSession
   * @return the sequence of file actions to save in the commit log(add, remove...)
   */
  def write(txn: OptimisticTransaction, sparkSession: SparkSession): Seq[Action] = {

    import sparkSession.implicits._
    if (txn.readVersion > -1) {
      // This table already exists, check if the insert is valid.
      if (mode == SaveMode.ErrorIfExists) {
        throw AnalysisExceptionFactory.create(s"Path '${deltaLog.dataPath}' already exists.'")
      } else if (mode == SaveMode.Ignore) {
        return Nil
      } else if (mode == SaveMode.Overwrite) {
        deltaLog.assertRemovable()
      }
    }
    val rearrangeOnly = options.rearrangeOnly

    updateMetadata(txn, data, partitionColumns, Map.empty, isOverwriteOperation, rearrangeOnly)

    // Validate partition predicates
    val replaceWhere = options.replaceWhere
    val partitionFilters = if (replaceWhere.isDefined) {
      val predicates = parsePartitionPredicates(sparkSession, replaceWhere.get)
      if (mode == SaveMode.Overwrite) {
        verifyPartitionPredicates(sparkSession, txn.metadata.partitionColumns, predicates)
      }
      Some(predicates)
    } else {
      None
    }

    if (txn.readVersion < 0) {
      // Initialize the log path
      val fs = deltaLog.logPath.getFileSystem(sparkSession.sessionState.newHadoopConf)

      fs.mkdirs(deltaLog.logPath)
    }

    val (qbeastData, spaceRevision, weightMap) =
      if (mode == SaveMode.Overwrite || qbeastSnapshot.isInitial ||
        !qbeastSnapshot.lastSpaceRevision.contains(data, columnsToIndex)) {
        oTreeAlgorithm.indexFirst(data, columnsToIndex)
      } else {
        oTreeAlgorithm.indexNext(data, qbeastSnapshot, announcedSet)
      }

    val newFiles = writeFiles(qbeastData, spaceRevision, weightMap)
    val addFiles = newFiles.collect { case a: AddFile => a }
    val deletedFiles = (mode, partitionFilters) match {
      case (SaveMode.Overwrite, None) =>
        txn.filterFiles().map(_.remove)
      case (SaveMode.Overwrite, Some(predicates)) =>
        // Check to make sure the files we wrote out were actually valid.
        val matchingFiles = DeltaLog
          .filterFileList(txn.metadata.partitionSchema, addFiles.toDF(), predicates)
          .as[AddFile]
          .collect()
        val invalidFiles = addFiles.toSet -- matchingFiles
        if (invalidFiles.nonEmpty) {
          val badPartitions = invalidFiles
            .map(_.partitionValues)
            .map {
              _.map { case (k, v) => s"$k=$v" }.mkString("/")
            }
            .mkString(", ")
          throw AnalysisExceptionFactory.create(
            s"""Data written out does not match replaceWhere '$replaceWhere'.
               |Invalid data would be written to partitions $badPartitions.""".stripMargin)
        }

        txn.filterFiles(predicates).map(_.remove)
      case _ => Nil
    }

    if (rearrangeOnly) {
      addFiles.map(_.copy(dataChange = !rearrangeOnly)) ++
        deletedFiles.map(_.copy(dataChange = !rearrangeOnly))
    } else {
      newFiles ++ deletedFiles
    }

  }

  /**
   * Writes qbeast indexed data into files
   * @param qbeastData the dataFrame containing data to write
   * @param spaceRevision the space revision of the data
   * @return the sequence of added files to the table
   */

  def writeFiles(
      qbeastData: DataFrame,
      spaceRevision: SpaceRevision,
      weightMap: Map[CubeId, Weight]): Seq[FileAction] = {

    val (factory: OutputWriterFactory, serConf: SerializableConfiguration) = {
      val format = new ParquetFileFormat()
      val job = Job.getInstance()
      (
        format.prepareWrite(data.sparkSession, job, Map.empty, data.schema),
        new SerializableConfiguration(job.getConfiguration))
    }
    val qbeastColumns = QbeastColumns(qbeastData)

    val blockWriter =
      BlockWriter(
        dataPath = deltaLog.dataPath.toString,
        schema = data.schema,
        schemaIndex = qbeastData.schema,
        factory = factory,
        serConf = serConf,
        qbeastColumns = qbeastColumns,
        columnsToIndex = columnsToIndex,
        spaceRevision = spaceRevision,
        weightMap = weightMap)
    qbeastData
      .repartition(col(cubeColumnName), col(stateColumnName))
      .queryExecution
      .executedPlan
      .execute
      .mapPartitions(blockWriter.writeRow)
      .collect()

  }

  override protected val canMergeSchema: Boolean = true
  override protected val canOverwriteSchema: Boolean = true
}

/**
 * QbeastWriter companion object.
 */
object QbeastWriter {

  /**
   * Use this constructor to store metadata of the indexed columns while creating a QbeastWriter
   * @param mode SaveMode of the write
   * @param deltaLog deltaLog associated to the table
   * @param options options for write operation
   * @param partitionColumns partition columns
   * @param data data to write
   * @param columnsToIndex qbeast columns to index
   * @param qbeastSnapshot current qbeast snapshot of the table
   * @param announcedSet set of cubes announced
   * @param oTreeAlgorithm algorithm to organize data
   * @return a new QbeastWriter
   */
  def apply(
      mode: SaveMode,
      deltaLog: DeltaLog,
      options: DeltaOptions,
      partitionColumns: Seq[String],
      data: DataFrame,
      columnsToIndex: Seq[String],
      qbeastSnapshot: QbeastSnapshot,
      announcedSet: Set[CubeId],
      oTreeAlgorithm: OTreeAlgorithm): QbeastWriter = {

    // Store metadata in the indexedColumns, setting the value to false in the rest of columns.
    // This preserves previously stored metadata as well (except isQbeastIndexedColumn).
    var newData = data
    for (c <- newData.columns) {
      val isIndexedColumn = columnsToIndex.contains(c).toString
      val oldMetadata = newData.schema(c).metadata.json
      if (oldMetadata.equals("{}")) {
        val newMetadata =
          new MetadataBuilder().putString("isQbeastIndexedColumn", isIndexedColumn).build()
        newData = newData
          .withColumn(c, newData.col(c).as("", newMetadata))
      } else {
        val newMetadata = Metadata.fromJson(
          oldMetadata
            .substring(
              0,
              oldMetadata.length - 1) + ",\"isQbeastIndexedColumn\":" + isIndexedColumn + "}")
        newData = newData
          .withColumn(c, newData.col(c).as("", newMetadata))
      }
    }

    new QbeastWriter(
      mode,
      deltaLog,
      options,
      partitionColumns,
      newData,
      columnsToIndex,
      qbeastSnapshot,
      announcedSet,
      oTreeAlgorithm)
  }

}
