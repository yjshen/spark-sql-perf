/*
 * Copyright 2015 Databricks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.sql.perf

import java.util.UUID

import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkEnv, SparkContext}

import scala.collection.mutable.ArrayBuffer

/** A trait to describe things that can be benchmarked. */
trait Benchmarkable {
  val sqlContext = SQLContext.getOrCreate(SparkContext.getOrCreate())
  val sparkContext = sqlContext.sparkContext

  val name: String
  protected val executionMode: ExecutionMode

  final def benchmark(
      includeBreakdown: Boolean,
      description: String = "",
      messages: ArrayBuffer[String],
      timeout: Long): BenchmarkResult = {
    sparkContext.setJobDescription(s"Execution: $name, $description")
    beforeBenchmark()
    val result = runBenchmark(includeBreakdown, description, messages, timeout)
    afterBenchmark(sqlContext.sparkContext)
    result
  }

  protected def beforeBenchmark(): Unit = { }

  private def afterBenchmark(sc: SparkContext): Unit = {
    // Best-effort clean up of weakly referenced RDDs, shuffles, and broadcasts
    System.gc()
    // Remove any leftover blocks that still exist
    sc.getExecutorStorageStatus
        .flatMap { status => status.blocks.map { case (bid, _) => bid } }
        .foreach { bid => SparkEnv.get.blockManager.master.removeBlock(bid) }
  }

  private def runBenchmark(
      includeBreakdown: Boolean,
      description: String = "",
      messages: ArrayBuffer[String],
      timeout: Long): BenchmarkResult = {
    val jobgroup = UUID.randomUUID().toString
    var result: BenchmarkResult = null
    val thread = new Thread("benchmark runner") {
      override def run(): Unit = {
        sparkContext.setJobGroup(jobgroup, s"benchmark $name", true)
        result = doBenchmark(includeBreakdown, description, messages)
      }
    }
    thread.setDaemon(true)
    thread.start()
    thread.join(timeout)
    if (thread.isAlive) {
      sparkContext.cancelJobGroup(jobgroup)
      thread.interrupt()
      result = BenchmarkResult(
        name = name,
        mode = executionMode.toString,
        failure = Some(Failure("Timeout", s"timeout after ${timeout / 1000} seconds"))
      )
    }
    result
  }

  protected def doBenchmark(
      includeBreakdown: Boolean,
      description: String = "",
      messages: ArrayBuffer[String]): BenchmarkResult

  protected def measureTimeMs[A](f: => A): Double = {
    val startTime = System.nanoTime()
    f
    val endTime = System.nanoTime()
    (endTime - startTime).toDouble / 1000000
  }
}