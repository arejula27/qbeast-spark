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

import io.qbeast.core.model.QTableID
import io.qbeast.core.model.TableChanges
import io.qbeast.core.model.WriteMode.WriteModeValue
import io.qbeast.spark.internal.QbeastOptions
import org.apache.spark.sql.delta.actions.Action
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.actions.RemoveFile
import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.delta.OptimisticTransaction
import org.apache.spark.sql.types.StructType

class MetadataWriterTest(
    tableID: QTableID,
    mode: WriteModeValue,
    deltaLog: DeltaLog,
    options: QbeastOptions,
    schema: StructType)
    extends DeltaMetadataWriter(tableID, mode, deltaLog, options, schema) {

  // Make updateMetadata method public for test scope
  override def updateMetadata(
      txn: OptimisticTransaction,
      tableChanges: TableChanges,
      addFiles: Seq[AddFile],
      removeFiles: Seq[RemoveFile],
      extraConfiguration: Configuration): Seq[Action] =
    super.updateMetadata(txn, tableChanges, addFiles, removeFiles, extraConfiguration)

}

object MetadataWriterTest {

  def apply(
      tableID: QTableID,
      mode: WriteModeValue,
      deltaLog: DeltaLog,
      options: QbeastOptions,
      schema: StructType): MetadataWriterTest = {
    new MetadataWriterTest(tableID, mode, deltaLog, options, schema)
  }

}
