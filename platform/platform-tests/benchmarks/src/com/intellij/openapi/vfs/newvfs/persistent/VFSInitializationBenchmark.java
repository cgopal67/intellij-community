// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.platform.diagnostic.telemetry.TelemetryManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks VFS (FSRecords) initialization duration
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(15)
@Threads(1)
public class VFSInitializationBenchmark {


  @State(Scope.Benchmark)
  public static class Context {

    @Param({"true", "false"})
    public boolean warmupOpenTelemetry;

    public Path temporaryFolder;
    public Path realFolder;

    private PersistentFSConnection connectionToClose;


    @Setup
    public void setup() throws Exception {
      temporaryFolder = FileUtil.createTempDirectory(getClass().getSimpleName(), "tst", /*deleteOnExit: */ true).toPath();
      String realVfsFolderPath = System.getProperty("real-vfs-folder-to-benchmark-against");
      if (realVfsFolderPath == null) {
        realFolder = null;
      }
      else {
        realFolder = Paths.get(realVfsFolderPath);
      }

      if (warmupOpenTelemetry) {
        TelemetryManager.Companion.getInstance();
      }
      connectionToClose = null;
    }

    @TearDown
    public void tearDown() throws Exception {
      if (connectionToClose != null) {
        PersistentFSConnector.disconnect(connectionToClose);
        connectionToClose = null;
      }
      if (temporaryFolder != null) {
        FileUtilRt.deleteRecursively(temporaryFolder);
      }

      //IdeEventQueue.applicationClose();
      ShutDownTracker.getInstance().run();
      AppExecutorUtil.shutdownApplicationScheduledExecutorService();
    }
  }


  @Benchmark
  public void initEmptyVFS(Context context) throws IOException {
    int version = FSRecordsImpl.currentImplementationVersion();
    Path cachesDir = context.temporaryFolder;

    context.connectionToClose = initVFS(cachesDir, version, new Ref<>());
  }

  @Benchmark
  public void initVFS_OverAlreadyExistingFiles(Context context) throws IOException {
    int version = FSRecordsImpl.currentImplementationVersion();
    Path cachesDir = context.realFolder;
    if (cachesDir != null) {
      PersistentFSConnection connection = initVFS(cachesDir, version, new Ref<>());
      int maxAllocatedID = connection.getRecords().maxAllocatedID();
      assert maxAllocatedID > 100_000 : "maxAllocatedID" + maxAllocatedID + " is too low, probably already existing files are dummy?";
      context.connectionToClose = initVFS(cachesDir, version, new Ref<>());
    }
  }

  @Benchmark
  public void initVFS_OverAlreadyExistingFiles_AndWaitForInvertedNamesIndex(Context context) throws IOException {
    int version = FSRecordsImpl.currentImplementationVersion();
    Path cachesDir = context.realFolder;
    if (cachesDir != null) {
      Ref<NotNullLazyValue<InvertedNameIndex>> invertedNameIndexRef = new Ref<>();
      PersistentFSConnection connection = initVFS(cachesDir, version, invertedNameIndexRef);
      invertedNameIndexRef.get().getValue();
      int maxAllocatedID = connection.getRecords().maxAllocatedID();
      assert maxAllocatedID > 100_000 : "maxAllocatedID" + maxAllocatedID + " is too low, probably already existing files are dummy?";
      context.connectionToClose = connection;
    }
  }

  private static PersistentFSConnection initVFS(Path cachesDir,
                                                int version,
                                                Ref<NotNullLazyValue<InvertedNameIndex>> invertedNameIndexRef) {
    PersistentFSConnector.InitializationResult initResult = PersistentFSConnector.connect(
      cachesDir,
      version,
      true,
      invertedNameIndexRef,
      Collections.emptyList()
    );
    return initResult.connection;
  }


  public static void main(final String[] args) throws RunnerException {
    System.out.println("Real VFS path to bench: " + args[0]);
    final Options opt = new OptionsBuilder()
      .jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED",
               "--add-opens=java.base/java.util=ALL-UNNAMED",
               "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
               "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
               "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
               "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",

               "-Dreal-vfs-folder-to-benchmark-against=" + args[0],
               
               "-Dvfs.parallelize-initialization=true"
      )
      .include(VFSInitializationBenchmark.class.getSimpleName() + ".*")
      .build();

    new Runner(opt).run();
  }
}
