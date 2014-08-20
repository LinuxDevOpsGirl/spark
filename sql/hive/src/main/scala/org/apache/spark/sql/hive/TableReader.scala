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

package org.apache.spark.sql.hive

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants._
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.hive.ql.metadata.{Partition => HivePartition, Table => HiveTable}
import org.apache.hadoop.hive.ql.plan.{PlanUtils, TableDesc}
import org.apache.hadoop.hive.serde2.Deserializer
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector

import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapred.{FileInputFormat, InputFormat, JobConf}

import org.apache.spark.SerializableWritable
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.{EmptyRDD, HadoopRDD, RDD, UnionRDD}

import org.apache.spark.sql.catalyst.expressions.{Attribute, Row, GenericMutableRow, Literal, Cast}
import org.apache.spark.sql.catalyst.types.DataType

/**
 * A trait for subclasses that handle table scans.
 */
private[hive] sealed trait TableReader {
  def makeRDDForTable(hiveTable: HiveTable): RDD[Row]

  def makeRDDForPartitionedTable(partitions: Seq[HivePartition]): RDD[Row]
}


/**
 * Helper class for scanning tables stored in Hadoop - e.g., to read Hive tables that reside in the
 * data warehouse directory.
 */
private[hive]
class HadoopTableReader(
    @transient attributes: Seq[Attribute],
    @transient relation: MetastoreRelation,
    @transient sc: HiveContext)
  extends TableReader {

  // Choose the minimum number of splits. If mapred.map.tasks is set, then use that unless
  // it is smaller than what Spark suggests.
  private val _minSplitsPerRDD = math.max(
    sc.hiveconf.getInt("mapred.map.tasks", 1), sc.sparkContext.defaultMinPartitions)

  // TODO: set aws s3 credentials.

  private val _broadcastedHiveConf =
    sc.sparkContext.broadcast(new SerializableWritable(sc.hiveconf))

  def broadcastedHiveConf = _broadcastedHiveConf

  def hiveConf = _broadcastedHiveConf.value.value

  override def makeRDDForTable(hiveTable: HiveTable): RDD[Row] =
    makeRDDForTable(
      hiveTable,
      relation.tableDesc.getDeserializerClass.asInstanceOf[Class[Deserializer]],
      filterOpt = None)

  /**
   * Creates a Hadoop RDD to read data from the target table's data directory. Returns a transformed
   * RDD that contains deserialized rows.
   *
   * @param hiveTable Hive metadata for the table being scanned.
   * @param deserializerClass Class of the SerDe used to deserialize Writables read from Hadoop.
   * @param filterOpt If defined, then the filter is used to reject files contained in the data
   *                  directory being read. If None, then all files are accepted.
   */
  def makeRDDForTable(
      hiveTable: HiveTable,
      deserializerClass: Class[_ <: Deserializer],
      filterOpt: Option[PathFilter]): RDD[Row] = {

    assert(!hiveTable.isPartitioned, """makeRDDForTable() cannot be called on a partitioned table,
      since input formats may differ across partitions. Use makeRDDForTablePartitions() instead.""")

    // Create local references to member variables, so that the entire `this` object won't be
    // serialized in the closure below.
    val tableDesc = relation.tableDesc
    val broadcastedHiveConf = _broadcastedHiveConf

    val tablePath = hiveTable.getPath
    val inputPathStr = applyFilterIfNeeded(tablePath, filterOpt)

    // logDebug("Table input: %s".format(tablePath))
    val ifc = hiveTable.getInputFormatClass
      .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
    val hadoopRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)

    val attrsWithIndex = attributes.zipWithIndex
    val mutableRow = new GenericMutableRow(attrsWithIndex.length)
    val deserializedHadoopRDD = hadoopRDD.mapPartitions { iter =>
      val hconf = broadcastedHiveConf.value.value
      val deserializer = deserializerClass.newInstance()
      deserializer.initialize(hconf, tableDesc.getProperties)

      HadoopTableReader.fillObject(iter, deserializer, attrsWithIndex, mutableRow)
    }

    deserializedHadoopRDD
  }

  override def makeRDDForPartitionedTable(partitions: Seq[HivePartition]): RDD[Row] = {
    val partitionToDeserializer = partitions.map(part =>
      (part, part.getDeserializer.getClass.asInstanceOf[Class[Deserializer]])).toMap
    makeRDDForPartitionedTable(partitionToDeserializer, filterOpt = None)
  }

  /**
   * Create a HadoopRDD for every partition key specified in the query. Note that for on-disk Hive
   * tables, a data directory is created for each partition corresponding to keys specified using
   * 'PARTITION BY'.
   *
   * @param partitionToDeserializer Mapping from a Hive Partition metadata object to the SerDe
   *     class to use to deserialize input Writables from the corresponding partition.
   * @param filterOpt If defined, then the filter is used to reject files contained in the data
   *     subdirectory of each partition being read. If None, then all files are accepted.
   */
  def makeRDDForPartitionedTable(
      partitionToDeserializer: Map[HivePartition,
      Class[_ <: Deserializer]],
      filterOpt: Option[PathFilter]): RDD[Row] = {
    val hivePartitionRDDs = partitionToDeserializer.map { case (partition, partDeserializer) =>
      val partDesc = Utilities.getPartitionDesc(partition)
      val partPath = partition.getPartitionPath
      val inputPathStr = applyFilterIfNeeded(partPath, filterOpt)
      val ifc = partDesc.getInputFileFormatClass
        .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
      // Get partition field info
      val partSpec = partDesc.getPartSpec
      val partProps = partDesc.getProperties

      val partColsDelimited: String = partProps.getProperty(META_TABLE_PARTITION_COLUMNS)
      // Partitioning columns are delimited by "/"
      val partCols = partColsDelimited.trim().split("/").toSeq
      // 'partValues[i]' contains the value for the partitioning column at 'partCols[i]'.
      val partValues = if (partSpec == null) {
        Array.fill(partCols.size)(new String)
      } else {
        partCols.map(col => new String(partSpec.get(col))).toArray
      }

      // Create local references so that the outer object isn't serialized.
      val tableDesc = relation.tableDesc
      val broadcastedHiveConf = _broadcastedHiveConf
      val localDeserializer = partDeserializer
      val mutableRow = new GenericMutableRow(attributes.length)

      // split the attributes (output schema) into 2 categories:
      // (partition keys, ordinal), (normal attributes, ordinal), the ordinal mean the 
      // index of the attribute in the output Row.
      val (partitionKeys, attrs) = attributes.zipWithIndex.partition(attr => {
        relation.partitionKeys.indexOf(attr._1) >= 0
      })

      def fillPartitionKeys(parts: Array[String], row: GenericMutableRow) = {
        partitionKeys.foreach { case (attr, ordinal) =>
          // get partition key ordinal for a given attribute
          val partOridinal = relation.partitionKeys.indexOf(attr)
          row(ordinal) = Cast(Literal(parts(partOridinal)), attr.dataType).eval(null)
        }
      }
      // fill the partition key for the given MutableRow Object
      fillPartitionKeys(partValues, mutableRow)

      val hivePartitionRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)
      hivePartitionRDD.mapPartitions { iter =>
        val hconf = broadcastedHiveConf.value.value
        val deserializer = localDeserializer.newInstance()
        deserializer.initialize(hconf, partProps)

        // fill the non partition key attributes 
        HadoopTableReader.fillObject(iter, deserializer, attrs, mutableRow)
      }
    }.toSeq

    // Even if we don't use any partitions, we still need an empty RDD
    if (hivePartitionRDDs.size == 0) {
      new EmptyRDD[Row](sc.sparkContext)
    } else {
      new UnionRDD(hivePartitionRDDs(0).context, hivePartitionRDDs)
    }
  }

  /**
   * If `filterOpt` is defined, then it will be used to filter files from `path`. These files are
   * returned in a single, comma-separated string.
   */
  private def applyFilterIfNeeded(path: Path, filterOpt: Option[PathFilter]): String = {
    filterOpt match {
      case Some(filter) =>
        val fs = path.getFileSystem(sc.hiveconf)
        val filteredFiles = fs.listStatus(path, filter).map(_.getPath.toString)
        filteredFiles.mkString(",")
      case None => path.toString
    }
  }

  /**
   * Creates a HadoopRDD based on the broadcasted HiveConf and other job properties that will be
   * applied locally on each slave.
   */
  private def createHadoopRdd(
    tableDesc: TableDesc,
    path: String,
    inputFormatClass: Class[InputFormat[Writable, Writable]]): RDD[Writable] = {

    val initializeJobConfFunc = HadoopTableReader.initializeLocalJobConfFunc(path, tableDesc) _

    val rdd = new HadoopRDD(
      sc.sparkContext,
      _broadcastedHiveConf.asInstanceOf[Broadcast[SerializableWritable[Configuration]]],
      Some(initializeJobConfFunc),
      inputFormatClass,
      classOf[Writable],
      classOf[Writable],
      _minSplitsPerRDD)

    // Only take the value (skip the key) because Hive works only with values.
    rdd.map(_._2)
  }
}

private[hive] object HadoopTableReader extends HiveInspectors {
  /**
   * Curried. After given an argument for 'path', the resulting JobConf => Unit closure is used to
   * instantiate a HadoopRDD.
   */
  def initializeLocalJobConfFunc(path: String, tableDesc: TableDesc)(jobConf: JobConf) {
    FileInputFormat.setInputPaths(jobConf, path)
    if (tableDesc != null) {
      PlanUtils.configureInputJobPropertiesForStorageHandler(tableDesc)
      Utilities.copyTableJobPropertiesToConf(tableDesc, jobConf)
    }
    val bufferSize = System.getProperty("spark.buffer.size", "65536")
    jobConf.set("io.file.buffer.size", bufferSize)
  }

  /**
   * Transform the raw data(Writable object) into the Row object for an iterable input
   * @param iter Iterable input which represented as Writable object
   * @param deserializer Deserializer associated with the input writable object
   * @param attrs Represents the row attribute names and its zero-based position in the MutableRow
   * @param row reusable MutableRow object
   * 
   * @return Iterable Row object that transformed from the given iterable input.
   */
  def fillObject(
      iter: Iterator[Writable],
      deserializer: Deserializer,
      attrs: Seq[(Attribute, Int)],
      row: GenericMutableRow): Iterator[Row] = {
    val soi = deserializer.getObjectInspector().asInstanceOf[StructObjectInspector]
    // get the field references according to the attributes(output of the reader) required
    val fieldRefs = attrs.map { case (attr, idx) => (soi.getStructFieldRef(attr.name), idx) }

    // Map each tuple to a row object
    iter.map { value =>
      val raw = deserializer.deserialize(value)
      var idx = 0;
      while (idx < fieldRefs.length) {
        val fieldRef = fieldRefs(idx)._1
        val fieldIdx = fieldRefs(idx)._2
        val fieldValue = soi.getStructFieldData(raw, fieldRef)

        row(fieldIdx) = unwrapData(fieldValue, fieldRef.getFieldObjectInspector())

        idx += 1
      }

      row: Row
    }
  }
}
