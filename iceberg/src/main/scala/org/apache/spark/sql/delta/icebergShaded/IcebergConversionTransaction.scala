/*
 * Copyright (2021) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta.icebergShaded

import java.util.ConcurrentModificationException
import java.util.function.Consumer

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import org.apache.spark.sql.delta.{DeltaFileProviderUtils, IcebergConstants, NoMapping, Snapshot}
import org.apache.spark.sql.delta.actions.{AddFile, Metadata, RemoveFile}
import org.apache.spark.sql.delta.icebergShaded.IcebergTransactionUtils._
import org.apache.spark.sql.delta.logging.DeltaLogKeys
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.hadoop.conf.Configuration
import shadedForDelta.org.apache.iceberg.{AppendFiles, DataFile, DeleteFiles, ExpireSnapshots, OverwriteFiles, PartitionSpec, PendingUpdate, RewriteFiles, Schema => IcebergSchema, Transaction => IcebergTransaction}
import shadedForDelta.org.apache.iceberg.mapping.MappingUtil
import shadedForDelta.org.apache.iceberg.mapping.NameMappingParser
import shadedForDelta.org.apache.iceberg.util.LocationUtil

import org.apache.spark.internal.MDC
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.CatalogTable

sealed trait IcebergTableOp
case object CREATE_TABLE extends IcebergTableOp
case object WRITE_TABLE extends IcebergTableOp
case object REPLACE_TABLE extends IcebergTableOp

/**
 * Used to prepare (convert) and then commit a set of Delta actions into the Iceberg table located
 * at the same path as [[postCommitSnapshot]]
 *
 *
 * @param conf Configuration for Iceberg Hadoop interactions.
 * @param postCommitSnapshot Latest Delta snapshot associated with this Iceberg commit.
 * @param tableOp How to instantiate the underlying Iceberg table. Defaults to WRITE_TABLE.
 * @param lastConvertedIcebergSnapshotId the iceberg snapshot this Iceberg txn should write to.
 * @param lastConvertedDeltaVersion the delta version this Iceberg txn starts from.
 */
class IcebergConversionTransaction(
    protected val spark: SparkSession,
    protected val catalogTable: CatalogTable,
    protected val conf: Configuration,
    protected val postCommitSnapshot: Snapshot,
    protected val tableOp: IcebergTableOp = WRITE_TABLE,
    protected val lastConvertedIcebergSnapshotId: Option[Long] = None,
    protected val lastConvertedDeltaVersion: Option[Long] = None
    ) extends DeltaLogging {

  ///////////////////////////
  // Nested Helper Classes //
  ///////////////////////////

  implicit class AddFileConversion(addFile: AddFile) {
    def toDataFile: DataFile =
      convertDeltaAddFileToIcebergDataFile(
        addFile,
        tablePath,
        currentPartitionSpec,
        logicalToPhysicalPartitionNames,
        statsParser,
        postCommitSnapshot)
  }

  implicit class RemoveFileConversion(removeFile: RemoveFile) {
    def toDataFile: DataFile =
      convertDeltaRemoveFileToIcebergDataFile(
        removeFile,
        tablePath,
        currentPartitionSpec,
        logicalToPhysicalPartitionNames,
        postCommitSnapshot)
  }

  protected abstract class TransactionHelper(protected val impl: PendingUpdate[_]) {
    protected var committed = false
    var writeSize = 0L

    def opType: String

    def add(add: AddFile): Unit = throw new UnsupportedOperationException
    def add(remove: RemoveFile): Unit = throw new UnsupportedOperationException

    def commit(expectedSequenceNumber: Long): Unit = {
      assert(!committed, "Already committed.")
      impl.commit()
      committed = true
    }

    private[icebergShaded]def hasCommitted: Boolean = committed

    protected def currentSnapshotId: Option[Long] =
      Option(txn.table().currentSnapshot()).map(_.snapshotId())
  }

  class NullHelper extends TransactionHelper(null) {
    override def opType: String = "null"
    override def add(add: AddFile): Unit = {}
    override def add(remove: RemoveFile): Unit = {}
    override def commit(deltaCommitVersion: Long): Unit = {}
  }
  /**
   * API for appending new files in a table.
   *
   * e.g. INSERT
   */
  class AppendOnlyHelper(appender: AppendFiles) extends TransactionHelper(appender) {

    override def opType: String = "append"

    override def add(add: AddFile): Unit = {
      writeSize += add.size
      appender.appendFile(add.toDataFile)
    }
  }

  /**
   * API for deleting files from a table.
   *
   * e.g. DELETE
   */
  class RemoveOnlyHelper(deleter: DeleteFiles) extends TransactionHelper(deleter) {

    override def opType: String = "delete"

    override def add(remove: RemoveFile): Unit = {
      // We can just use the canonical RemoveFile.path instead of converting RemoveFile to DataFile.
      // Note that in other helper APIs, converting a FileAction to a DataFile will also take care
      // of canonicalizing the path.
      deleter.deleteFile(canonicalizeFilePath(remove, tablePath))
    }
  }

  /**
   * API for overwriting files in a table. Replaces all the deleted files with the set of additions.
   *
   * e.g. UPDATE, MERGE
   */
  class OverwriteHelper(overwriter: OverwriteFiles) extends TransactionHelper(overwriter) {

    override def opType: String = "overwrite"

    override def add(add: AddFile): Unit = {
      writeSize += add.size
      overwriter.addFile(add.toDataFile)
    }

    override def add(remove: RemoveFile): Unit = {
      overwriter.deleteFile(remove.toDataFile)
    }
  }

  /**
   * API for rewriting existing files in the table (i.e. replaces one set of data files with another
   * set that contains the same data).
   *
   * e.g. OPTIMIZE
   */
  class RewriteHelper(rewriter: RewriteFiles) extends TransactionHelper(rewriter) {

    override def opType: String = "rewrite"

    private val addBuffer: mutable.HashSet[DataFile] = new mutable.HashSet[DataFile]
    private val removeBuffer: mutable.HashSet[DataFile] = new mutable.HashSet[DataFile]

    override def add(add: AddFile): Unit = {
      writeSize += add.size
      assert(!add.dataChange, "Rewrite operation should not add data")
      addBuffer += add.toDataFile
    }

    override def add(remove: RemoveFile): Unit = {
      assert(!remove.dataChange, "Rewrite operation should not add data")
      removeBuffer += remove.toDataFile
    }

    override def commit(deltaCommitVersion: Long): Unit = {
      if (removeBuffer.nonEmpty) {
        rewriter.rewriteFiles(removeBuffer.asJava, addBuffer.asJava, 0)
      }
      currentSnapshotId.foreach(rewriter.validateFromSnapshot)
      super.commit(deltaCommitVersion)
    }
  }

  class ExpireSnapshotHelper(expireSnapshot: ExpireSnapshots)
      extends TransactionHelper(expireSnapshot) {

    def cleanExpiredFiles(clean: Boolean): ExpireSnapshotHelper = {
      expireSnapshot.cleanExpiredFiles(clean)
      this
    }

    def deleteWith(newDeleteFunc: Consumer[String]): ExpireSnapshotHelper = {
      expireSnapshot.deleteWith(newDeleteFunc)
      this
    }

    override def opType: String = "expireSnapshot"
  }

  //////////////////////
  // Member variables //
  //////////////////////

  protected val tablePath = postCommitSnapshot.deltaLog.dataPath

  protected val convert = new DeltaToIcebergConvert(postCommitSnapshot, catalogTable)

  protected def icebergSchema: IcebergSchema = convert.schema

  // Initial partition spec converted from Delta
  protected def partitionSpec: PartitionSpec = convert.partition


  // Current partition spec from iceberg table
  def currentPartitionSpec: PartitionSpec = {
    Some(txn.table()).map(_.spec()).getOrElse(partitionSpec)
  }

  protected val logicalToPhysicalPartitionNames =
    getPartitionPhysicalNameMapping(postCommitSnapshot.metadata.partitionSchema)

  /** Parses the stats JSON string to convert Delta stats to Iceberg stats. */
  private val statsParser =
    DeltaFileProviderUtils.createJsonStatsParser(postCommitSnapshot.statsSchema)

  /** Visible for testing. */
  private[icebergShaded]val (txn, startFromSnapshotId) = withStartSnapshotId(createIcebergTxn())

  /** Tracks if this transaction has already committed. You can only commit once. */
  private var committed = false

  /** Tracks the file updates (add, remove, overwrite, rewrite) made to this table. */
  protected val fileUpdates = new ArrayBuffer[TransactionHelper]()

  /** Tracks if this transaction updates only the differences between a prev and new metadata. */
  private var isMetadataUpdate = false

  /////////////////
  // Public APIs //
  /////////////////
  def getNullHelper: NullHelper = new NullHelper()

  def getAppendOnlyHelper: AppendOnlyHelper = {
    val ret = new AppendOnlyHelper(txn.newAppend())
    fileUpdates += ret
    ret
  }

  def getRemoveOnlyHelper: RemoveOnlyHelper = {
    val ret = new RemoveOnlyHelper(txn.newDelete())
    fileUpdates += ret
    ret
  }

  def getOverwriteHelper: OverwriteHelper = {
    val ret = new OverwriteHelper(txn.newOverwrite())
    fileUpdates += ret
    ret
  }

  def getRewriteHelper: RewriteHelper = {
    val ret = new RewriteHelper(txn.newRewrite())
    fileUpdates += ret
    ret
  }

  def getExpireSnapshotHelper(): ExpireSnapshotHelper = {
    val ret = new ExpireSnapshotHelper(txn.expireSnapshots())
    fileUpdates += ret
    ret
  }

  /**
   * Handles the following update scenarios
   * - partition update -> throws
   * - schema update -> sets the full new schema
   * - properties update -> applies only the new properties
   */
  def updateTableMetadata(newMetadata: Metadata, prevMetadata: Metadata): Unit = {
    assert(!isMetadataUpdate, "updateTableMetadata already called")
    isMetadataUpdate = true

    // Throws if partition evolution detected
    if (newMetadata.partitionColumns != prevMetadata.partitionColumns) {
      throw new IllegalStateException("Delta does not support partition evolution")
    }


    // As we do not have a second set schema txn for REPLACE_TABLE, we need to set
    // the schema as part of this transaction
    if (newMetadata.schema != prevMetadata.schema || tableOp == REPLACE_TABLE) {
      val differenceStr = SchemaUtils.reportDifferences(prevMetadata.schema, newMetadata.schema)
      logInfo(
        log"Detected schema update for table with name=" +
        log"${MDC(DeltaLogKeys.TABLE_NAME, newMetadata.name)}, " +
        log"id=${MDC(DeltaLogKeys.METADATA_ID, newMetadata.id)}:\n" +
        log"${MDC(DeltaLogKeys.SCHEMA_DIFF, differenceStr)}, " +
        s"tableOp=$tableOp, " +
        log"Setting new Iceberg schema:\n " +
        log"${MDC(DeltaLogKeys.SCHEMA, icebergSchema)}"
      )
      txn.setSchema(icebergSchema).commit()

      recordDeltaEvent(
        postCommitSnapshot.deltaLog,
        "delta.iceberg.conversion.schemaChange",
        data = Map(
          "version" -> postCommitSnapshot.version,
          "deltaSchemaDiff" -> differenceStr,
          "icebergSchema" -> icebergSchema.toString.replace('\n', ';')
        )
      )
    }

    val (propertyDeletes, propertyAdditions) =
      detectPropertiesChange(newMetadata.configuration, prevMetadata.configuration)

    if (propertyDeletes.nonEmpty || propertyAdditions.nonEmpty) {
      val updater = txn.updateProperties()
      propertyDeletes.foreach(updater.remove)
      propertyAdditions.foreach(kv => updater.set(kv._1, kv._2))
      updater.commit()

      recordDeltaEvent(
        postCommitSnapshot.deltaLog,
        "delta.iceberg.conversion.propertyChange",
        data = Map("version" -> postCommitSnapshot.version) ++
          (if (propertyDeletes.nonEmpty) Map("deletes" -> propertyDeletes.toSeq) else Map.empty) ++
          (if (propertyAdditions.nonEmpty) Map("adds" -> propertyAdditions) else Map.empty)
      )
    }
  }

  def commit(): Unit = {
    assert(!committed, "Cannot commit. Transaction already committed.")

    // At least one file or metadata updates is required when writing to an existing table. If
    // creating or replacing a table, we can create an empty table with just the table metadata
    // (schema, properties, etc.)
    if (tableOp == WRITE_TABLE) {
      assert(fileUpdates.nonEmpty || isMetadataUpdate, "Cannot commit WRITE. Transaction is empty.")
    }
    assert(fileUpdates.forall(_.hasCommitted), "Cannot commit. You have uncommitted changes.")

    val nameMapping = NameMappingParser.toJson(MappingUtil.create(icebergSchema))

    // hard code dummy delta version as -1 for CREATE_TABLE, which will be later
    // set to correct version in setSchemaTxn. -1 is chosen because it is less than the smallest
    // possible legitimate Delta version which is 0.
    val deltaVersion = if (tableOp == CREATE_TABLE) -1 else postCommitSnapshot.version

    var updateTxn = txn.updateProperties()
    updateTxn = updateTxn.set(IcebergConverter.DELTA_VERSION_PROPERTY, deltaVersion.toString)
      .set(IcebergConverter.DELTA_TIMESTAMP_PROPERTY, postCommitSnapshot.timestamp.toString)
      .set(IcebergConstants.ICEBERG_NAME_MAPPING_PROPERTY, nameMapping)

    val includeBaseVersion = spark.sessionState.conf.getConf(
      DeltaSQLConf.DELTA_UNIFORM_ICEBERG_INCLUDE_BASE_CONVERTED_VERSION)
    updateTxn = lastConvertedDeltaVersion match {
      case Some(v) if includeBaseVersion =>
        updateTxn.set(IcebergConverter.BASE_DELTA_VERSION_PROPERTY, v.toString)
      case _ =>
        updateTxn.remove(IcebergConverter.BASE_DELTA_VERSION_PROPERTY)
    }
    updateTxn.commit()

    // We ensure the iceberg txns are serializable by only allowing them to commit against
    // lastConvertedIcebergSnapshotId.
    //
    // If the startFromSnapshotId is non-empty and not the same as lastConvertedIcebergSnapshotId,
    // there is a new iceberg transaction committed after we read lastConvertedIcebergSnapshotId,
    // and before this check. We explicitly abort by throwing exceptions.
    //
    // If startFromSnapshotId is empty, the txn must be one of the following:
    // 1. CREATE_TABLE
    // 2. Writing to an empty table
    // 3. REPLACE_TABLE
    // In either case this txn is safe to commit.
    //
    // Iceberg will further guarantee that txns passed this check are serializable.
    if (startFromSnapshotId.isDefined && lastConvertedIcebergSnapshotId != startFromSnapshotId) {
      throw new ConcurrentModificationException("Cannot commit because the converted " +
        s"metadata is based on a stale iceberg snapshot $lastConvertedIcebergSnapshotId"
      )
    }
    try {
      txn.commitTransaction()
      if (tableOp == CREATE_TABLE) {
        // Iceberg CREATE_TABLE reassigns the field id in schema, which
        // is overwritten by setting Delta schema with Delta generated field id to ensure
        // consistency between field id in Iceberg schema after conversion and field id in
        // parquet files written by Delta.
        val setSchemaTxn = createIcebergTxn(Some(WRITE_TABLE))
        setSchemaTxn.setSchema(icebergSchema).commit()
        setSchemaTxn.updateProperties()
          .set(IcebergConverter.DELTA_VERSION_PROPERTY, postCommitSnapshot.version.toString)
          .commit()
        setSchemaTxn.commitTransaction()
      }
      recordIcebergCommit()
    } catch {
      case NonFatal(e) =>
        recordIcebergCommit(Some(e))
        throw e
    }

    committed = true
  }

  ///////////////////////
  // Protected Methods //
  ///////////////////////

  protected def createIcebergTxn(tableOpOpt: Option[IcebergTableOp] = None):
      IcebergTransaction = {
    val hiveCatalog = IcebergTransactionUtils.createHiveCatalog(conf)
    val icebergTableId = IcebergTransactionUtils
      .convertSparkTableIdentifierToIcebergHive(catalogTable.identifier)

    val tableExists = hiveCatalog.tableExists(icebergTableId)

    def tableBuilder = {
      val properties = DeltaToIcebergConvert.TableProperties(
        postCommitSnapshot.metadata.configuration
      )

      hiveCatalog
        .buildTable(icebergTableId, icebergSchema)
        .withPartitionSpec(partitionSpec)
        .withProperties(properties.asJava)
    }

    tableOpOpt.getOrElse(tableOp) match {
      case WRITE_TABLE =>
        if (tableExists) {
          recordFrameProfile("IcebergConversionTransaction", "loadTable") {
            hiveCatalog.loadTable(icebergTableId).newTransaction()
          }
        } else {
          throw new IllegalStateException(s"Cannot write to table $tablePath. Table doesn't exist.")
        }
      case CREATE_TABLE =>
        if (tableExists) {
          throw new IllegalStateException(s"Cannot create table $tablePath. Table already exists.")
        } else {
          recordFrameProfile("IcebergConversionTransaction", "createTable") {
            tableBuilder.createTransaction()
          }
        }
      case REPLACE_TABLE =>
        if (tableExists) {
          recordFrameProfile("IcebergConversionTransaction", "replaceTable") {
            tableBuilder.replaceTransaction()
          }
        } else {
          throw new IllegalStateException(s"Cannot replace table $tablePath. Table doesn't exist.")
        }
    }
  }

  ////////////////////
  // Helper Methods //
  ////////////////////

  /**
   * We fetch the txn table's current snapshot id before any writing is made on the transaction.
   * This id should equal [[lastConvertedIcebergSnapshotId]] for the transaction to commit.
   *
   * @param txn the iceberg transaction
   * @return txn and the snapshot id just before this txn
   */
  private def withStartSnapshotId(txn: IcebergTransaction): (IcebergTransaction, Option[Long]) =
    (txn, Option(txn.table().currentSnapshot()).map(_.snapshotId()))

  private def recordIcebergCommit(errorOpt: Option[Throwable] = None): Unit = {
    val icebergTxnTypes =
      if (fileUpdates.nonEmpty) Map("icebergTxnTypes" -> fileUpdates.map(_.opType)) else Map.empty

    val errorData = errorOpt.map { e =>
      Map(
        "exception" -> ExceptionUtils.getMessage(e),
        "stackTrace" -> ExceptionUtils.getStackTrace(e)
      )
    }.getOrElse(Map.empty)


    recordDeltaEvent(
      postCommitSnapshot.deltaLog,
      s"delta.iceberg.conversion.commit.${if (errorOpt.isEmpty) "success" else "error"}",
      data = Map(
        "version" -> postCommitSnapshot.version,
        "timestamp" -> postCommitSnapshot.timestamp,
        "tableOp" -> tableOp.getClass.getSimpleName.stripSuffix("$"),
        "prevConvertedDeltaVersion" -> lastConvertedDeltaVersion,
        "tableSize" -> postCommitSnapshot.sizeInBytes,
        "commitWriteSize" -> fileUpdates.map(_.writeSize).sum
      ) ++ icebergTxnTypes ++ errorData
    )
  }

}
