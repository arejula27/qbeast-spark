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
package io.qbeast.core.model

import io.qbeast.IISeq

sealed trait QbeastFile extends Serializable {

  def path: String

  def size: Long

  def dataChange: Boolean

  override def toString: String = {
    s"QbeastFile($path, $size, $dataChange)"
  }

}

/**
 * Index file represents a physical file where blocks of the elements are stored.
 * @param path
 *   the file path
 * @param size
 *   the file size in bytes
 * @param dataChange
 *   the data change flag
 * @param modificationTime
 *   the modification time
 * @param revisionId
 *   the revision identifier
 * @param blocks
 *   the blocks
 * @param stats
 *   the statistics
 */
case class IndexFile(
    path: String,
    size: Long,
    dataChange: Boolean,
    modificationTime: Long,
    revisionId: RevisionID,
    blocks: IISeq[Block],
    stats: Option[String] = None)
    extends QbeastFile {

  /**
   * The number of elements in the file
   *
   * @return
   *   the number of elements
   */
  def elementCount: Long = blocks.map(_.elementCount).sum

  /**
   * Returns whether file contains data from a given cube.
   *
   * @param cubeId
   *   the cube identifier
   * @return
   *   the file contains data of the cube
   */
  def hasCubeData(cubeId: CubeId): Boolean = blocks.exists(_.cubeId == cubeId)

  override def toString: String = {
    s"IndexFile($path, $size, $modificationTime, $revisionId, $blocks)"
  }

}

/**
 * Index file represents a physical file where blocks of the elements are stored.
 *
 * @param path
 *   the file path
 * @param size
 *   the file size in bytes
 * @param deletionTimestamp
 *   the deletion timestamp
 */
case class DeleteFile(path: String, size: Long, dataChange: Boolean, deletionTimestamp: Long)
    extends QbeastFile {

  override def toString: String = {
    s"DeleteFile($path, $size, $dataChange, $deletionTimestamp)"
  }

}
