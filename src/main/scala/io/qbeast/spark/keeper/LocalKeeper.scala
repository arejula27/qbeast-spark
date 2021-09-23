/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.keeper

import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementation of Keeper which saves announced set in memory. This implementation is used
 * when no other implementation can be obtained from ServiceLoader.
 */
object LocalKeeper extends Keeper {
  private val generator = new AtomicInteger()
  private val announcedMap = scala.collection.mutable.Map.empty[(String, Long), Set[String]]

  override def beginWrite(indexId: String, revision: Long): Write = new LocalWrite(
    generator.getAndIncrement().toString,
    announcedMap.getOrElse((indexId, revision), Set.empty[String]))

  override def announce(indexId: String, revision: Long, cubes: Seq[String]): Unit = {
    val announcedCubes = announcedMap.getOrElse((indexId, revision), Set.empty[String])
    announcedMap.update((indexId, revision), announcedCubes.union(cubes.toSet))
  }

  override def beginOptimization(
      indexId: String,
      revision: Long,
      cubeLimit: Integer): Optimization = new LocalOptimization(
    generator.getAndIncrement().toString,
    announcedMap.getOrElse((indexId, revision), Set.empty[String]))

  override def stop(): Unit = {}
}

private class LocalWrite(val id: String, override val announcedCubes: Set[String]) extends Write {

  override def end(): Unit = {}
}

private class LocalOptimization(val id: String, override val cubesToOptimize: Set[String])
    extends Optimization {

  override def end(replicatedCubes: Set[String]): Unit = {}
}
