// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent

import com.intellij.ide.IdeBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.RawProgressReporter
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.util.io.DataInputOutputUtilRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS.Flags.MUST_RELOAD_CONTENT
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS.Flags.MUST_RELOAD_LENGTH
import com.intellij.openapi.vfs.newvfs.persistent.log.*
import com.intellij.openapi.vfs.newvfs.persistent.log.IteratorUtils.constCopier
import com.intellij.openapi.vfs.newvfs.persistent.log.OperationLogStorage.OperationReadResult.*
import com.intellij.openapi.vfs.newvfs.persistent.log.OperationLogStorage.TraverseDirection
import com.intellij.openapi.vfs.newvfs.persistent.log.VfsOperation.AttributesOperation.Companion.fileId
import com.intellij.openapi.vfs.newvfs.persistent.log.VfsOperation.RecordsOperation.Companion.fileId
import com.intellij.openapi.vfs.newvfs.persistent.log.io.PersistentVar
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.*
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.SnapshotFillerPresets.buildFiller
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.SnapshotFillerPresets.constrain
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.SnapshotFillerPresets.sum
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.SnapshotFillerPresets.toFiller
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.State.Companion.get
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.State.Companion.getOrNull
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.State.NotAvailable
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.State.Ready
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.VfsSnapshot.VirtualFileSnapshot.Companion.isDeleted
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.VfsSnapshot.VirtualFileSnapshot.Property.Companion.get
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.VfsSnapshot.VirtualFileSnapshot.Property.Companion.getOrNull
import com.intellij.util.SystemProperties
import com.intellij.util.io.*
import org.jetbrains.annotations.ApiStatus
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.*
import kotlin.io.path.*
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@ApiStatus.Experimental
object VfsRecoveryUtils {
  fun createStoragesReplacementMarker(oldCachesDir: Path, newCachesDir: Path) {
    val marker = PersistentFSPaths(oldCachesDir).storagesReplacementMarkerFile
    marker.deleteIfExists()
    marker.writeLines(
      listOf(oldCachesDir.relativize(newCachesDir).pathString)
    )
  }

  @OptIn(ExperimentalPathApi::class)
  fun applyStoragesReplacementIfMarkerExists(cachesDir: Path): Boolean {
    val marker = PersistentFSPaths(cachesDir).storagesReplacementMarkerFile
    if (marker.notExists()) return false

    val newCachesDirPath = marker.readLines().firstOrNull()
    LOG.info("storages replacement marker detected: new caches content is in $newCachesDirPath")
    marker.delete() // consume
    if (newCachesDirPath == null) return false

    val newCachesDir = cachesDir.resolve(newCachesDirPath).normalize()
    if (newCachesDir == cachesDir) {
      LOG.error("storages replacement marker points to the existing caches directory. Leaving old caches as is")
      return false
    }
    if (!newCachesDir.isDirectory()) {
      LOG.info("$newCachesDir is not a directory. Leaving old caches as is")
      return false
    }
    if (PersistentFSPaths(newCachesDir).storagesReplacementMarkerFile.exists()) {
      LOG.info("new caches content contains a replacement marker too. Leaving old caches as is")
      return false
    }

    val backupDir = cachesDir.parent / "caches-backup"
    if (backupDir.exists()) {
      LOG.info("deleting an old backup")
      backupDir.deleteRecursively()
    }

    cachesDir.moveTo(backupDir)
    LOG.info("created a backup successfully")

    newCachesDir.moveTo(cachesDir, true)
    LOG.info("successfully replaced storages")
    return true
  }

  data class RecoveryResult(
    var fileStateCounts: Map<RecoveryState, Int> = emptyMap(),
    var recoveredAttributesCount: Long = 0,
    var botchedAttributesCount: Long = 0,
    var recoveredContentsCount: Int = 0,
    var lostContentsCount: Int = 0,
    var duplicateChildrenDeduplicated: Int = 0,
    var duplicateChildrenLost: Int = 0,
    var duplicateChildrenCount: Int = 0,
    var recoveryTime: Duration = Duration.INFINITE,
    var details: String? = null
  )

  enum class RecoveryState {
    UNDEFINED,
    INITIALIZED,
    CONNECTED,
    UNUSED,
    BOTCHED
  }

  private class FileStateController {
    private val states = arrayListOf<RecoveryState>()
    private val counts = arrayListOf<Int>(*(RecoveryState.values().map { 0 }.toTypedArray()))

    private fun alterCount(before: RecoveryState?, after: RecoveryState) {
      before?.let { counts[it.ordinal]-- }
      counts[after.ordinal]++
    }

    fun getState(fileId: Int): RecoveryState {
      if (fileId >= states.size) {
        states.ensureCapacity(fileId + 1)
        val toAdd = fileId + 1 - states.size
        repeat(toAdd) {
          states.add(RecoveryState.UNDEFINED)
        }
        counts[RecoveryState.UNDEFINED.ordinal] += toAdd
      }
      return states[fileId]
    }

    fun setState(fileId: Int, state: RecoveryState) {
      alterCount(getState(fileId), state)
      states[fileId] = state
    }

    fun getCount(state: RecoveryState): Int = counts[state.ordinal]

    fun getStatistics() = RecoveryState.values().associateWith { counts[it.ordinal] }

    override fun toString(): String = getStatistics().toString()
  }

  private class RecoveryContext(
    val recoveryResult: RecoveryResult,
    val point: () -> OperationLogStorage.Iterator,
    val queryContext: VfsLogQueryContext,
    val newFsRecords: FSRecordsImpl,
    val newVfsLog: VfsLogImpl,
    val progressReporter: RawProgressReporter?,
    private val vfsTimeMachine: SinglePassVfsTimeMachine,
    private val setFiller: (SnapshotFillerPresets.Filler) -> Unit,
    private val compactedVfs: ExtendedVfsSnapshot?,
    val payloadReader: PayloadReader = queryContext.payloadReader,
    val fileStates: FileStateController = FileStateController(),
  ) : AutoCloseable {
    val childrenAttributeEnumerated = queryContext.enumerateAttribute(PersistentFSTreeAccessor.CHILDREN_ATTR)

    fun getSnapshot(filler: SnapshotFillerPresets.Filler): ExtendedVfsSnapshot {
      setFiller(filler)
      val snapshot = vfsTimeMachine.getSnapshot(point()).precededByCompactedInstance()
      return snapshot
    }

    private fun ExtendedVfsSnapshot.precededByCompactedInstance(): ExtendedVfsSnapshot =
      compactedVfs?.let { this.precededBy(it) } ?: this

    var lastAllocatedRecord = superRootId
      private set

    // root record is expected to be initialized already
    fun ensureAllocated(fileId: Int) {
      while (lastAllocatedRecord < fileId) {
        val newRecord = newFsRecords.createRecord()
        check(newRecord == lastAllocatedRecord + 1)
        lastAllocatedRecord = newRecord
      }
    }

    override fun close() {
      newVfsLog.awaitPendingWrites()
      newVfsLog.dispose()
      newFsRecords.dispose()
    }
  }

  private const val superRootId = PersistentFSTreeAccessor.SUPER_ROOT_ID

  // nameId != getName(fileId) for a child of super root!
  private data class SuperRootChild(val fileId: Int, val nameId: Int)

  /**
   * Enumerators must be ok as they are copied into the new VFS caches directory.
   *
   * WARN: attribute record ids are not preserved across recoveries, moreover subsequent recovery may leave VfsLog in a state
   *       that will produce incorrect attribute record ids in snapshots. Attributes themselves are _not_ affected, because their
   *       recovery is based on file ids and not attribute record ids.
   */
  fun recoverFromPoint(
    point: OperationLogStorage.Iterator,
    queryContext: VfsLogQueryContext,
    oldStorageDir: Path,
    newStorageDir: Path,
    progressReporter: RawProgressReporter? = null,
    invokeReadAction: (Computable<*>) -> Any = { ApplicationManager.getApplication().runReadAction(it) }
  ) = wrapRecovery {
    check(oldStorageDir.isDirectory())
    FileUtil.ensureExists(newStorageDir.toFile())
    newStorageDir.forEachDirectoryEntry { throw IllegalArgumentException("directory for recovered vfs is not empty") }

    fun recoveryFail(msg: String, cause: Throwable? = null): Nothing = throw VfsRecoveryException(msg, cause)

    val ctx: RecoveryContext = invokeReadAction(Computable {
      val namesEnumFile = "names"
      val attrEnumFile = "attributes_enums"
      copyFilesStartingWith(namesEnumFile, oldStorageDir, newStorageDir)
      copyFilesStartingWith(attrEnumFile, oldStorageDir, newStorageDir)

      val newStoragePaths = PersistentFSPaths(newStorageDir)
      val namesEnum = PersistentStringEnumerator(newStoragePaths.storagePath(namesEnumFile), true)
      val attributeEnumerator = SimpleStringPersistentEnumerator(newStoragePaths.storagePath(attrEnumFile))

      val safeNameDeenum: (Int) -> State.DefinedState<String> = {
        try {
          namesEnum.valueOf(it)?.let(State::Ready) ?: NotAvailable("null name for $it")
        }
        catch (e: Throwable) {
          NotAvailable("failed to get name $it", e)
        }
      }
      val fillerHolder = object {
        var filler: SnapshotFillerPresets.Filler? = null // single thread hence not volatile
      }
      val compactedVfs = queryContext.getBaseSnapshot(safeNameDeenum) { attributeEnumerator }
      val vtm = SinglePassVfsTimeMachine(queryContext, safeNameDeenum, { attributeEnumerator }) { fillerHolder.filler!! }

      val newFsRecords = FSRecordsImpl.connect(
        newStorageDir,
        emptyList(), // vfs log is altered manually, tracking every operation anew is too costly
        FSRecordsImpl.ErrorHandler { records, error ->
          recoveryFail("Failed to recover VFS due to an error in new FSRecords", error)
        }
      )

      copyVfsLog(oldStorageDir, newStorageDir, point.copy())
      val newVfsLog = VfsLogImpl(PersistentFSPaths(newStorageDir).vfsLogStorage, false)

      RecoveryContext(this, point.constCopier(), queryContext, newFsRecords, newVfsLog, progressReporter,
                      vtm, { fillerHolder.filler = it }, compactedVfs)
    }) as RecoveryContext

    ctx.use {
      val (maxFileId, childrenCacheMap, superRootChildrenByAttr) = ctx.buildChildrenCacheMap()

      val stages = 4
      fun reportStage(stage: Int) = progressReporter?.text(IdeBundle.message("progress.cache.recover.from.logs.stage", stage, stages))

      reportStage(1)
      val lastRecoveredContentId = ctx.recoverAvailableContents()

      reportStage(2)
      ctx.initializeRecords(lastRecoveredContentId, maxFileId)

      reportStage(3)
      ctx.setupChildrenAttr(childrenCacheMap, superRootChildrenByAttr, maxFileId)

      // mark unused as deleted
      reportStage(4)
      ctx.markUnusedAsDeleted(maxFileId)

      fileStateCounts = ctx.fileStates.getStatistics()
    }

    patchVfsCreationTimestamp(oldStorageDir, newStorageDir)
  }

  private fun RecoveryContext.buildChildrenCacheMap(): Triple<Int, Map<Int, List<Int>>, List<SuperRootChild>> {
    val parentIdAndSuperRootAttrsFiller = listOf(
      SnapshotFillerPresets.RulePropertyRelations.parentId.toFiller(),
      SnapshotFillerPresets.attributesFiller.constrain {
        this is VfsOperation.AttributesOperation && fileId == superRootId
      }
    ).sum()
    val snapshot = getSnapshot(parentIdAndSuperRootAttrsFiller)

    val superRootChildrenData =
      snapshot.getFileById(superRootId).attributeDataMap.getOrNull()?.get(childrenAttributeEnumerated)?.let(payloadReader)
      ?: throw VfsRecoveryException("Failed to recover VFS because super-root data is unavailable")

    val superRootChildrenByAttr = when (superRootChildrenData) {
      is NotAvailable -> throw VfsRecoveryException("Failed to recover VFS because super-root data is unavailable",
                                                    superRootChildrenData.cause)
      is Ready -> {
        try { // TODO this probably should be extracted to a common method (as [PersistentFSTreeAccessor.saveNameIdSequenceWithDeltas])
          val contentStream = DataInputStream(UnsyncByteArrayInputStream(superRootChildrenData.value))
          if (PersistentFSTreeAccessor.CHILDREN_ATTR.isVersioned) {
            val version = DataInputOutputUtil.readINT(contentStream)
            if (version != childrenAttributeEnumerated.version) {
              throw IllegalStateException("version mismatch: expected ${childrenAttributeEnumerated.version} vs actual $version")
            }
          }
          val count = DataInputOutputUtil.readINT(contentStream)
          val nameIds = mutableListOf<Int>()
          val fileIds = mutableListOf<Int>()
          check(count >= 0)
          repeat(count) {
            val nameId = DataInputOutputUtil.readINT(contentStream)
            val fileId = DataInputOutputUtil.readINT(contentStream)
            nameIds.add((nameIds.lastOrNull() ?: 0) + nameId)
            fileIds.add((fileIds.lastOrNull() ?: 0) + fileId)
          }
          fileIds.zip(nameIds).map { SuperRootChild(it.first, it.second) }
        }
        catch (e: Throwable) {
          throw VfsRecoveryException("Failed to parse super-root data", e)
        }
      }
    }

    var maxFileId = superRootId
    val childrenCacheMap: Map<Int, List<Int>> = run {
      val result = mutableMapOf<Int, MutableList<Int>>()
      snapshot.forEachFile {
        if (maxFileId < it.fileId) maxFileId = it.fileId
        it.parentId.getOrNull()?.let { parentId ->
          result.compute(parentId) { _, children ->
            if (children == null) return@compute mutableListOf(it.fileId)
            children.add(it.fileId)
            children
          }
        }
      }
      result
    }

    return Triple(maxFileId, childrenCacheMap, superRootChildrenByAttr)
  }

  private fun RecoveryContext.recoverAvailableContents(): Int {
    var lastRecoveredContentId = 0
    val snapshot = getSnapshot(SnapshotFillerPresets.contentFiller)
    while (true) {
      when (val nextContent = snapshot.getContent(lastRecoveredContentId + 1)) {
        is NotAvailable -> break
        is Ready -> {
          val result = newFsRecords.contentAccessor().allocateContentRecordAndStore(nextContent.value)
          check(result == lastRecoveredContentId + 1) { "assumption failed: got $result, expected ${lastRecoveredContentId + 1}" }
          lastRecoveredContentId++
        }
      }
    }
    return lastRecoveredContentId
  }

  private fun RecoveryContext.initializeRecords(
    lastRecoveredContentId: Int,
    maxFileId: Int,
  ) {
    val newVfsLogOperationWriteContext = newVfsLog.getOperationWriteContext()
    // TODO estimate memory consumption more precisely and find out what fits into ~1GB(?)
    val initChunkSize = SystemProperties.getIntProperty("idea.vfs-recovery.records-init-chunk-size", 750_000)
    for (chunkStart in superRootId..maxFileId step initChunkSize) {
      val chunkEnd = (chunkStart + initChunkSize).coerceAtMost(maxFileId) + 1 // excluded
      val chunkRange = chunkStart until chunkEnd
      // reinit snapshot to free memory
      val snapshot = getSnapshot(
        listOf(SnapshotFillerPresets.RulePropertyRelations.allProperties.buildFiller(), SnapshotFillerPresets.attributesFiller).sum()
          .constrain {
            val fileId = getFileId()
            fileId == null || fileId in chunkRange
          }
      )

      for (file in chunkRange.map { snapshot.getFileById(it) }) {
        ensureAllocated(file.fileId)
        if (file.fileId == superRootId) {
          fileStates.setState(file.fileId, RecoveryState.INITIALIZED)
          continue
        }
        try {
          if (!file.isAvailable()) {
            fileStates.setState(file.fileId, RecoveryState.BOTCHED)
            continue
          }
          if (file.isDeleted.get()) {
            fileStates.setState(file.fileId, RecoveryState.UNUSED)
            continue
          }
          // set fields
          newFsRecords.fillRecord(file.fileId, file.timestamp.get(), file.length.get(), file.flags.get(), file.nameId.get(),
                                  file.parentId.get(), true)
          // recover content if available
          val contentRecordId = file.contentRecordId.get()
          if (contentRecordId != 0) {
            if (contentRecordId <= lastRecoveredContentId) {
              newFsRecords.connection().records.setContentRecordId(file.fileId, contentRecordId)
              recoveryResult.recoveredContentsCount++
            }
            else {
              val flags = file.flags.get() or MUST_RELOAD_CONTENT or MUST_RELOAD_LENGTH
              newFsRecords.setFlags(file.fileId, flags)
              recoveryResult.lostContentsCount++
              // append content record id clearing operation for fileId. This is needed so that subsequent recovery, being invoked on at a
              // point after ctx.point(), given that this contentRecordId will be recoverable at that point (with a content that is
              // different from what was expected in the old vfs at ctx.point()), won't set the obsolete content record id to the file
              newVfsLogOperationWriteContext.enqueueOperationWrite(VfsOperationTag.REC_SET_CONTENT_RECORD_ID) {
                VfsOperation.RecordsOperation.SetContentRecordId(file.fileId, 0, OperationResult.fromValue(true))
              }
              newVfsLogOperationWriteContext.enqueueOperationWrite(VfsOperationTag.REC_SET_FLAGS) {
                VfsOperation.RecordsOperation.SetFlags(file.fileId, flags, OperationResult.fromValue(true))
              }
            }
          }
          // recover available attrs except children
          for ((enumeratedAttrId, dataRef) in file.attributeDataMap.get()) {
            if (enumeratedAttrId == childrenAttributeEnumerated) continue
            val attr = queryContext.deenumerateAttribute(enumeratedAttrId) ?: throw IllegalStateException(
              "cannot deenumerate attribute using vfslog enumerator (enumeratedAttribute=$enumeratedAttrId)")
            val attrData = payloadReader(dataRef)
            if (attrData !is Ready) continue // skip if NotAvailable
            val attrContent = attrData.value
                                .cutOutAttributeVersionPrefix(attr) ?: continue // TODO this doesn't look like it should be here
            try {
              newFsRecords.writeAttribute(file.fileId, attr).use {
                it.write(attrContent)
              }
              recoveryResult.recoveredAttributesCount++
            }
            catch (e: Throwable) {
              recoveryResult.botchedAttributesCount++
              if (e is IOException) {
                throw e
              }
            }
          }
          fileStates.setState(file.fileId, RecoveryState.INITIALIZED)
        }
        catch (e: Throwable) {
          fileStates.setState(file.fileId, RecoveryState.BOTCHED)
          continue
        }
        val initializedFiles = file.fileId
        progressReporter?.fraction((file.fileId.toDouble() / maxFileId).coerceIn(0.0, 1.0))
        if ((initializedFiles and 0xFF) == 0) {
          progressReporter?.details(IdeBundle.message("progress.cache.recover.from.logs.files.processed", initializedFiles, maxFileId))
        }
      }
    }
  }

  private fun RecoveryContext.setupChildrenAttr(childrenCacheMap: Map<Int, List<Int>>,
                                                superRootChildrenByAttr: List<SuperRootChild>,
                                                maxFileId: Int) {
    fun childrenOf(fileId: Int): Set<Int> =
      childrenCacheMap[fileId]?.toSet() ?: emptySet()

    val superRootChildren =
      superRootChildrenByAttr.map { it.fileId }.toSet().let { superRootChildrenByAttrIds ->
        childrenOf(0) // yes, not superRootId == 1
          .filter { it in superRootChildrenByAttrIds }
      }

    val snapshot = getSnapshot(SnapshotFillerPresets.attributesFiller)
    val superRootValidChildren = superRootChildren.filter { fileStates.getState(it) == RecoveryState.INITIALIZED }
    // root children attr is a special case
    newFsRecords.writeAttribute(superRootId, PersistentFSTreeAccessor.CHILDREN_ATTR).use { output ->
      val childrenToSave = superRootValidChildren.toSet()
      val (ids, names) = superRootChildrenByAttr.filter { it.fileId in childrenToSave }
        .sortedBy { it.fileId }.map { it.fileId to it.nameId }.unzip()
      PersistentFSTreeAccessor.saveNameIdSequenceWithDeltas(names.toIntArray(), ids.toIntArray(), output)
    }
    val recoveryQueueIds = ArrayDeque<Int>()
    recoveryQueueIds.addAll(superRootValidChildren)
    fileStates.setState(superRootId, RecoveryState.CONNECTED)
    superRootValidChildren.forEach {
      fileStates.setState(it, RecoveryState.CONNECTED)
    }
    var duplicateChildrenLogged = 0
    while (recoveryQueueIds.isNotEmpty()) {
      val fileId = recoveryQueueIds.pop()
      val validChildren: List<Int> = childrenOf(fileId)
        .filter { fileStates.getState(it) == RecoveryState.INITIALIZED }
        .let { children -> // filter out same nameId children
          val childrenIdAndNameId = children.zip(children.map(newFsRecords::getNameIdByFileId))
          val nameIdToChildrenIds: Map<Int, List<Int>> = childrenIdAndNameId.toMap().entries.groupBy({ it.value }, { it.key })
          val nameIdToDeduplicatedChildId: Map<Int, Int?> =
            nameIdToChildrenIds.mapValues { (nameId, childrenIds) ->
              if (childrenIds.size == 1) childrenIds[0]
              else {
                check(childrenIds.size > 1)
                val childrenByAttr: List<Int>? =
                  snapshot.getFileById(fileId).readAttribute(PersistentFSTreeAccessor.CHILDREN_ATTR).getOrNull()?.use { input ->
                    val count = DataInputOutputUtil.readINT(input)
                    var prevId = 0
                    val childrenByAttr = mutableListOf<Int>()
                    repeat(count) { _ ->
                      val id = DataInputOutputUtil.readINT(input) + prevId
                      prevId = id
                      childrenByAttr.add(id)
                    }
                    childrenByAttr
                  }
                if (childrenByAttr == null) null
                else {
                  val intersection = childrenIds.intersect(childrenByAttr.toSet())
                  duplicateChildrenLogged++
                  if (duplicateChildrenLogged <= 10) {
                    LOG.warn("duplicate children: fileId=$fileId, nameId=$nameId, children=$childrenIds, " +
                             "childrenByAttr=$childrenByAttr, intersection=${intersection.toList()}")
                  } else if (duplicateChildrenLogged == 11) {
                    LOG.warn("there are more duplicate children")
                  }
                  if (intersection.size == 1) intersection.first()
                  else null // empty || size > 1
                }
              }
            }
          nameIdToChildrenIds.forEach { (nameId, childrenBefore) ->
            if (childrenBefore.size > 1) {
              recoveryResult.duplicateChildrenCount += childrenBefore.size
              val deduplicationResult = nameIdToDeduplicatedChildId[nameId]
              if (deduplicationResult == null) {
                recoveryResult.duplicateChildrenLost += childrenBefore.size
              }
              else {
                recoveryResult.duplicateChildrenDeduplicated += 1
              }
            }
          }
          nameIdToDeduplicatedChildId.mapNotNull { it.value }
        }
      if (validChildren.isEmpty()) continue
      try {
        val idDeltas =
          (listOf(fileId) + validChildren.sorted())
            .zipWithNext()
            .map { it.second - it.first }
        newFsRecords.writeAttribute(fileId, PersistentFSTreeAccessor.CHILDREN_ATTR).use { out ->
          DataInputOutputUtil.writeINT(out, idDeltas.size)
          idDeltas.forEach {
            DataInputOutputUtil.writeINT(out, it)
          }
        }
        recoveryResult.recoveredAttributesCount++
        recoveryQueueIds.addAll(validChildren)
        validChildren.forEach { childId ->
          fileStates.setState(childId, RecoveryState.CONNECTED)
        }
      }
      catch (e: Throwable) {
        recoveryResult.botchedAttributesCount++
        fileStates.setState(fileId, RecoveryState.BOTCHED)
        validChildren.forEach { childId ->
          fileStates.setState(childId, RecoveryState.BOTCHED)
        }
        if (e is IOException) throw e
      }
      val connectedFiles = fileStates.getCount(RecoveryState.CONNECTED)
      progressReporter?.fraction((connectedFiles.toDouble() / maxFileId).coerceIn(0.0, 1.0))
      if ((connectedFiles and 0xFF) == 0) {
        progressReporter?.details(IdeBundle.message("progress.cache.recover.from.logs.files.processed", connectedFiles, maxFileId))
      }
    }
  }

  private fun RecoveryContext.markUnusedAsDeleted(maxFileId: Int) {
    for (recordId in (superRootId + 1)..lastAllocatedRecord) {
      if (fileStates.getState(recordId) !in listOf(RecoveryState.CONNECTED)) {
        if (fileStates.getState(recordId) != RecoveryState.BOTCHED) {
          fileStates.setState(recordId, RecoveryState.UNUSED)
        }
        try {
          newFsRecords.setFlags(recordId, PersistentFS.Flags.FREE_RECORD_FLAG)
        }
        catch (e: Throwable) {
          fileStates.setState(recordId, RecoveryState.BOTCHED)
        }
      }

      progressReporter?.fraction((recordId.toDouble() / lastAllocatedRecord).coerceIn(0.0, 1.0))
      if ((recordId and 0xFF) == 0) {
        progressReporter?.details(IdeBundle.message("progress.cache.recover.from.logs.files.processed", recordId, maxFileId))
      }
    }
  }

  private fun VfsOperation<*>.getFileId(): Int? = when (this) {
    is VfsOperation.RecordsOperation -> this.fileId
    is VfsOperation.AttributesOperation -> this.fileId
    else -> null
  }

  private fun copyVfsLog(oldStorageDir: Path,
                         newStorageDir: Path,
                         point: OperationLogStorage.Iterator) {
    if (oldStorageDir == newStorageDir) {
      throw IllegalArgumentException("oldStorageDir == newStorageDir")
    }
    try {
      (PersistentFS.getInstance().vfsLog as? VfsLogEx)?.flush()
    }
    catch (ignored: Throwable) {
    }
    // if there are pending writes it is okay, because we overwrite the size property anyway
    val oldPaths = PersistentFSPaths(oldStorageDir)
    val newPaths = PersistentFSPaths(newStorageDir)
    oldPaths.vfsLogStorage.copyRecursively(newPaths.vfsLogStorage)
    val operationsSizePath = newPaths.vfsLogStorage / "operations" / "size"
    if (!operationsSizePath.exists()) {
      throw VfsRecoveryException("vfslog operations size file not found")
    }
    else {
      try {
        var size by PersistentVar.long(operationsSizePath)
        size = point.getPosition()
      }
      catch (e: Throwable) {
        throw VfsRecoveryException("failed to truncate new vfslog", e)
      }
    }
  }

  private fun patchVfsCreationTimestamp(oldStorageDir: Path, newStorageDir: Path) {
    val oldRecords = PersistentFSPaths(oldStorageDir).storagePath("records")
    val newRecords = PersistentFSPaths(newStorageDir).storagePath("records")
    val oldTimestamp = oldRecords.toFile().inputStream().use {
      it.skipNBytes(PersistentFSHeaders.HEADER_TIMESTAMP_OFFSET.toLong())
      it.readNBytes(8)
    }
    if (oldTimestamp.size != 8) throw VfsRecoveryException("Failed to patch FSRecords timestamp: oldTimestamp.size != 8")
    ResilientFileChannel(newRecords, WRITE).use {
      it.position(PersistentFSHeaders.HEADER_TIMESTAMP_OFFSET.toLong())
      it.write(ByteBuffer.wrap(oldTimestamp))
    }
  }

  @OptIn(ExperimentalTime::class)
  private inline fun wrapRecovery(body: RecoveryResult.() -> Unit): RecoveryResult =
    try {
      val recoveryResult = RecoveryResult()
      recoveryResult.recoveryTime = measureTime { recoveryResult.body() }
      recoveryResult
    }
    catch (e: VfsRecoveryException) {
      throw e
    }
    catch (e: Throwable) {
      throw VfsRecoveryException("Failed to recover VFS", e)
    }

  private val VfsSnapshot.VirtualFileSnapshot.necessaryProps
    get() = listOf(nameId, parentId, length, timestamp, flags, contentRecordId)

  private fun VfsSnapshot.VirtualFileSnapshot.isAvailable(): Boolean =
    necessaryProps.all { it.observeState() is Ready<*> }

  private fun copyFilesStartingWith(prefix: String, fromDir: Path, toDir: Path) {
    fromDir.listDirectoryEntries("$prefix*").forEach {
      it.copyTo(toDir / it.fileName, true)
    }
  }

  private fun ByteArray.cutOutAttributeVersionPrefix(attr: FileAttribute): ByteArray? {
    if (!attr.isVersioned) return this
    // cut out prefix version because it will be added anyway inside the attributes accessor
    val buf = BufferExposingByteArrayOutputStream()
    DataInputOutputUtilRt.writeINT(DataOutputStream(buf), attr.version)
    val serializedVer = buf.toByteArray()
    if (this.size >= serializedVer.size && this.copyOfRange(0, serializedVer.size).contentEquals(serializedVer)) {
      return this.copyOfRange(serializedVer.size, this.size)
    }
    return null // version mismatch or corrupted (?) content
  }

  data class RecoveryPoint(val timestamp: Long, val point: OperationLogStorage.Iterator)

  /**
   * @return iterator <= [point], such that there are at least [completeOperationsAtLeast] preceding operations without
   * exceptions and incomplete descriptors
   */
  fun findClosestPrecedingPointWithNoIncompleteOperationsBeforeIt(
    point: () -> OperationLogStorage.Iterator,
    completeOperationsAtLeast: Int = 50_000
  ): OperationLogStorage.Iterator? {
    var candidate = point()
    out@ while (candidate.hasPrevious()) {
      val checkIter = candidate.copy()
      for (i in 1..completeOperationsAtLeast) {
        if (!checkIter.hasPrevious()) return null
        when (val result = checkIter.previous()) {
          is Complete -> {
            if (!result.operation.result.hasValue) { // exceptional operation
              candidate = checkIter.copy()
              continue@out
            }
          }
          is Incomplete -> {
            candidate = checkIter.copy()
            continue@out
          }
          is Invalid -> throw result.cause
        }
      }
      return candidate
    }
    return null
  }

  fun generateRecoveryPointsPriorTo(point: () -> OperationLogStorage.Iterator): Sequence<RecoveryPoint> = sequence {
    val iter = point()
    // position iter after a vfile event end operation
    VfsChronicle.traverseOperationsLog(iter, TraverseDirection.REWIND, VfsOperationTagsMask.ALL) {
      if (it is VfsOperation.VFileEventOperation.EventStart) {
        yield(RecoveryPoint(it.eventTimestamp, iter.copy()))
      }
    }
  }

  fun Sequence<RecoveryPoint>.thinOut(
    skipPeriodMsInit: Long = 30_000,
    periodMultiplier: Double = 1.618 // 30 sec * 1.618 ^ 20 ~= 5 days
  ): Sequence<RecoveryPoint> {
    val fiveYearsInMs = 5L * 365 * 24 * 60 * 60 * 1000
    return sequence {
      var skipPeriod: Long = skipPeriodMsInit
      var targetTimestamp: Long? = null
      for (rp in this@thinOut) {
        if (targetTimestamp == null) {
          yield(rp)
          targetTimestamp = rp.timestamp
        }
        else if (rp.timestamp + skipPeriod <= targetTimestamp) {
          yield(rp)
          skipPeriod = max(
            (skipPeriod * periodMultiplier).toLong(),
            targetTimestamp - rp.timestamp + skipPeriodMsInit
          )
          if (skipPeriod > fiveYearsInMs) break // make sure there is no overflow
        }
      }
    }
  }

  private val LOG = Logger.getInstance(VfsRecoveryUtils::class.java)
}