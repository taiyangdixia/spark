/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming

import java.io._
import java.nio.charset.StandardCharsets._

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.sql.execution.streaming.FakeFileSystem._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.test.SharedSQLContext

class CompactibleFileStreamLogSuite extends SparkFunSuite with SharedSQLContext {

  /** To avoid caching of FS objects */
  override protected val sparkConf =
    new SparkConf().set(s"spark.hadoop.fs.$scheme.impl.disable.cache", "true")

  import CompactibleFileStreamLog._

  /** -- testing of `object CompactibleFileStreamLog` begins -- */

  test("getBatchIdFromFileName") {
    assert(1234L === getBatchIdFromFileName("1234"))
    assert(1234L === getBatchIdFromFileName("1234.compact"))
    intercept[NumberFormatException] {
      getBatchIdFromFileName("1234a")
    }
  }

  test("isCompactionBatch") {
    assert(false === isCompactionBatch(0, compactInterval = 3))
    assert(false === isCompactionBatch(1, compactInterval = 3))
    assert(true === isCompactionBatch(2, compactInterval = 3))
    assert(false === isCompactionBatch(3, compactInterval = 3))
    assert(false === isCompactionBatch(4, compactInterval = 3))
    assert(true === isCompactionBatch(5, compactInterval = 3))
  }

  test("nextCompactionBatchId") {
    assert(2 === nextCompactionBatchId(0, compactInterval = 3))
    assert(2 === nextCompactionBatchId(1, compactInterval = 3))
    assert(5 === nextCompactionBatchId(2, compactInterval = 3))
    assert(5 === nextCompactionBatchId(3, compactInterval = 3))
    assert(5 === nextCompactionBatchId(4, compactInterval = 3))
    assert(8 === nextCompactionBatchId(5, compactInterval = 3))
  }

  test("getValidBatchesBeforeCompactionBatch") {
    intercept[AssertionError] {
      getValidBatchesBeforeCompactionBatch(0, compactInterval = 3)
    }
    intercept[AssertionError] {
      getValidBatchesBeforeCompactionBatch(1, compactInterval = 3)
    }
    assert(Seq(0, 1) === getValidBatchesBeforeCompactionBatch(2, compactInterval = 3))
    intercept[AssertionError] {
      getValidBatchesBeforeCompactionBatch(3, compactInterval = 3)
    }
    intercept[AssertionError] {
      getValidBatchesBeforeCompactionBatch(4, compactInterval = 3)
    }
    assert(Seq(2, 3, 4) === getValidBatchesBeforeCompactionBatch(5, compactInterval = 3))
  }

  test("getAllValidBatches") {
    assert(Seq(0) === getAllValidBatches(0, compactInterval = 3))
    assert(Seq(0, 1) === getAllValidBatches(1, compactInterval = 3))
    assert(Seq(2) === getAllValidBatches(2, compactInterval = 3))
    assert(Seq(2, 3) === getAllValidBatches(3, compactInterval = 3))
    assert(Seq(2, 3, 4) === getAllValidBatches(4, compactInterval = 3))
    assert(Seq(5) === getAllValidBatches(5, compactInterval = 3))
    assert(Seq(5, 6) === getAllValidBatches(6, compactInterval = 3))
    assert(Seq(5, 6, 7) === getAllValidBatches(7, compactInterval = 3))
    assert(Seq(8) === getAllValidBatches(8, compactInterval = 3))
  }

  test("deriveCompactInterval") {
    // latestCompactBatchId(4) + 1 <= default(5)
    // then use latestestCompactBatchId + 1 === 5
    assert(5 === deriveCompactInterval(5, 4))
    // First divisor of 10 greater than 4 === 5
    assert(5 === deriveCompactInterval(4, 9))
  }

  /** -- testing of `object CompactibleFileStreamLog` ends -- */

  test("batchIdToPath") {
    withFakeCompactibleFileStreamLog(
      fileCleanupDelayMs = Long.MaxValue,
      defaultCompactInterval = 3,
      compactibleLog => {
        assert("0" === compactibleLog.batchIdToPath(0).getName)
        assert("1" === compactibleLog.batchIdToPath(1).getName)
        assert("2.compact" === compactibleLog.batchIdToPath(2).getName)
        assert("3" === compactibleLog.batchIdToPath(3).getName)
        assert("4" === compactibleLog.batchIdToPath(4).getName)
        assert("5.compact" === compactibleLog.batchIdToPath(5).getName)
      })
  }

  test("serialize") {
    withFakeCompactibleFileStreamLog(
      fileCleanupDelayMs = Long.MaxValue,
      defaultCompactInterval = 3,
      compactibleLog => {
        val logs = Array("entry_1", "entry_2", "entry_3")
        val expected = s"""${FakeCompactibleFileStreamLog.VERSION}
            |"entry_1"
            |"entry_2"
            |"entry_3"""".stripMargin
        val baos = new ByteArrayOutputStream()
        compactibleLog.serialize(logs, baos)
        assert(expected === baos.toString(UTF_8.name()))

        baos.reset()
        compactibleLog.serialize(Array(), baos)
        assert(FakeCompactibleFileStreamLog.VERSION === baos.toString(UTF_8.name()))
      })
  }

  test("deserialize") {
    withFakeCompactibleFileStreamLog(
      fileCleanupDelayMs = Long.MaxValue,
      defaultCompactInterval = 3,
      compactibleLog => {
        val logs = s"""${FakeCompactibleFileStreamLog.VERSION}
            |"entry_1"
            |"entry_2"
            |"entry_3"""".stripMargin
        val expected = Array("entry_1", "entry_2", "entry_3")
        assert(expected ===
          compactibleLog.deserialize(new ByteArrayInputStream(logs.getBytes(UTF_8))))

        assert(Nil ===
          compactibleLog.deserialize(
            new ByteArrayInputStream(FakeCompactibleFileStreamLog.VERSION.getBytes(UTF_8))))
      })
  }

  testWithUninterruptibleThread("compact") {
    withFakeCompactibleFileStreamLog(
      fileCleanupDelayMs = Long.MaxValue,
      defaultCompactInterval = 3,
      compactibleLog => {
        for (batchId <- 0 to 10) {
          compactibleLog.add(batchId, Array("some_path_" + batchId))
          val expectedFiles = (0 to batchId).map { id => "some_path_" + id }
          assert(compactibleLog.allFiles() === expectedFiles)
          if (isCompactionBatch(batchId, 3)) {
            // Since batchId is a compaction batch, the batch log file should contain all logs
            assert(compactibleLog.get(batchId).getOrElse(Nil) === expectedFiles)
          }
        }
      })
  }

  testWithUninterruptibleThread("delete expired file") {
    // Set `fileCleanupDelayMs` to 0 so that we can detect the deleting behaviour deterministically
    withFakeCompactibleFileStreamLog(
      fileCleanupDelayMs = 0,
      defaultCompactInterval = 3,
      compactibleLog => {
        val fs = compactibleLog.metadataPath.getFileSystem(spark.sessionState.newHadoopConf())

        def listBatchFiles(): Set[String] = {
          fs.listStatus(compactibleLog.metadataPath).map(_.getPath.getName).filter { fileName =>
            try {
              getBatchIdFromFileName(fileName)
              true
            } catch {
              case _: NumberFormatException => false
            }
          }.toSet
        }

        compactibleLog.add(0, Array("some_path_0"))
        assert(Set("0") === listBatchFiles())
        compactibleLog.add(1, Array("some_path_1"))
        assert(Set("0", "1") === listBatchFiles())
        compactibleLog.add(2, Array("some_path_2"))
        assert(Set("2.compact") === listBatchFiles())
        compactibleLog.add(3, Array("some_path_3"))
        assert(Set("2.compact", "3") === listBatchFiles())
        compactibleLog.add(4, Array("some_path_4"))
        assert(Set("2.compact", "3", "4") === listBatchFiles())
        compactibleLog.add(5, Array("some_path_5"))
        assert(Set("5.compact") === listBatchFiles())
      })
  }

  private def withFakeCompactibleFileStreamLog(
    fileCleanupDelayMs: Long,
    defaultCompactInterval: Int,
    f: FakeCompactibleFileStreamLog => Unit
  ): Unit = {
    withTempDir { file =>
      val compactibleLog = new FakeCompactibleFileStreamLog(
        fileCleanupDelayMs,
        defaultCompactInterval,
        spark,
        file.getCanonicalPath)
      f(compactibleLog)
    }
  }
}

object FakeCompactibleFileStreamLog {
  val VERSION = "test_version"
}

class FakeCompactibleFileStreamLog(
    _fileCleanupDelayMs: Long,
    _defaultCompactInterval: Int,
    sparkSession: SparkSession,
    path: String)
  extends CompactibleFileStreamLog[String](
    FakeCompactibleFileStreamLog.VERSION,
    sparkSession,
    path
  ) {

  override protected def fileCleanupDelayMs: Long = _fileCleanupDelayMs

  override protected def isDeletingExpiredLog: Boolean = true

  override protected def defaultCompactInterval: Int = _defaultCompactInterval

  override def compactLogs(logs: Seq[String]): Seq[String] = logs
}
