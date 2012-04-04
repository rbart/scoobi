/**
  * Copyright 2011 National ICT Australia Limited
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package com.nicta.scoobi.io.func

import java.io.IOException
import java.io.DataInput
import java.io.DataOutput
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.io.Writable
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.hadoop.mapreduce.InputSplit
import org.apache.hadoop.mapreduce.RecordReader
import org.apache.hadoop.mapreduce.TaskAttemptContext
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.JobContext
import scala.collection.JavaConversions._

import com.nicta.scoobi.DList
import com.nicta.scoobi.WireFormat
import com.nicta.scoobi.io.DataSource
import com.nicta.scoobi.io.InputConverter
import com.nicta.scoobi.impl.plan.Smart
import com.nicta.scoobi.impl.plan.AST
import com.nicta.scoobi.impl.exec.DistCache
import com.nicta.scoobi.impl.util.UniqueInt


/** Smart function for creating a distributed lists from a Scala function. */
object FunctionInput {
  lazy val logger = LogFactory.getLog("scoobi.FunctionInput")

  /** Create a distributed list of a specified length whose elements are generated by
    * a function that maps list indices to element values. */
  def fromFunction[A : Manifest : WireFormat](n: Int)(f: Int => A): DList[A] = {
    val source = new DataSource[NullWritable, A, A] {
      private val id = FunctionId.get
      val inputFormat = classOf[FunctionInputFormat[A]]
      def inputCheck() = {}

      def inputConfigure(job: Job) = {
        val conf = job.getConfiguration
        conf.setInt(LengthProperty, n)
        conf.setInt(IdProperty, id)
        DistCache.pushObject(conf, f, functionProperty(id))
      }

      def inputSize(): Long = n.toLong

      val inputConverter = new InputConverter[NullWritable, A, A] {
        def fromKeyValue(context: InputContext, k: NullWritable, v: A) = v
      }
    }
    DList.fromSource(source)
  }

  /* Because FunctionInputFormat is shared between multiple instances of the Function
   * DataSource, each must have a unique id to distinguish their serialised
   * functions that are pushed out by the distributed cache. */
  object FunctionId extends UniqueInt

  /* Configuration property names. */
  private val PropertyPrefix = "scoobi.function"
  private val LengthProperty = PropertyPrefix + ".n"
  private val IdProperty = PropertyPrefix + ".id"
  private def functionProperty(id: Int) = PropertyPrefix + ".f" + id


  /** InputFormat for producing values based on a function. */
  class FunctionInputFormat[A] extends InputFormat[NullWritable, A] {

    def createRecordReader(split: InputSplit, context: TaskAttemptContext): RecordReader[NullWritable, A] =
      new FunctionRecordReader[A](split.asInstanceOf[FunctionInputSplit[A]])

    def getSplits(context: JobContext): java.util.List[InputSplit] = {
      val conf = context.getConfiguration
      val n = context.getConfiguration.getInt(LengthProperty, 0)
      val id = context.getConfiguration.getInt(IdProperty, 0)
      val f = DistCache.pullObject(context.getConfiguration, functionProperty(id)).asInstanceOf[Int => A]

      val numSplitsHint = conf.getInt("mapred.map.tasks", 1)
      val splitSize = n / numSplitsHint

      logger.debug("id=" + id)
      logger.debug("n=" + n)
      logger.debug("numSplitsHint=" + numSplitsHint)
      logger.debug("splitSize=" + splitSize)

      (0 to (n - 1)).grouped(splitSize)
                    .map { r => (r.head, r.size) }
                    .map { case (s, l) => new FunctionInputSplit[A](s, l, f) }
                    .toList
    }
  }


  /** InputSplit for a range of values produced by a function. */
  class FunctionInputSplit[A](var start: Int, var length: Int, var f: Int => A) extends InputSplit with Writable {

    def this() = this(0, 0, null.asInstanceOf[Int => A])

    def getLength: Long = length.toLong

    def getLocations: Array[String] = new Array[String](0)

    def readFields(in: DataInput) = {
      start = in.readInt()
      length = in.readInt()

      val size = in.readInt()
      val barr = new Array[Byte](size)
      in.readFully(barr)
      val bIn = new ObjectInputStream(new ByteArrayInputStream(barr))
      f = bIn.readObject.asInstanceOf[Int => A]
    }

    def write(out: DataOutput) = {
      out.writeInt(start)
      out.writeInt(length)

      val bytesOut = new ByteArrayOutputStream
      val bOut =  new ObjectOutputStream(bytesOut)
      bOut.writeObject(f)
      bOut.close()
      val arr = bytesOut.toByteArray
      out.writeInt(arr.size)
      out.write(arr)
    }
  }


  /** RecordReader for producing values based on a function. */
  class FunctionRecordReader[A](split: FunctionInputSplit[A]) extends RecordReader[NullWritable, A] {

    private val end = split.start + split.length
    private var ix = split.start
    private var x: A = _

    def initialize(split: InputSplit, context: TaskAttemptContext) = {}

    def getCurrentKey(): NullWritable = NullWritable.get

    def getCurrentValue(): A = x

    def getProgress(): Float = (ix - (end - split.length)) / split.length

    def nextKeyValue(): Boolean = {
      if (ix < end) {
        x = split.f(ix)
        ix += 1
        true
      } else {
        false
      }
    }

    def close() = {}
  }
}
