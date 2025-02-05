/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.exec.hadoop;

import static com.dremio.io.file.PathFilters.ALL_FILES;
import static com.dremio.io.file.PathFilters.NO_HIDDEN_FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.dremio.common.perf.StatsCollectionEligibilityRegistrar;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.OperatorProfile;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.FileSystemUtils;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OpProfileDef;
import com.dremio.sabot.exec.context.OperatorStats;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class TestHadoopFileSystemWrapper {

  private static Stream<Arguments> testToFsPermission() {
    return IntStream.range(0, 01000)
        .mapToObj(i -> Arguments.of (FsPermission.createImmutable((short) i), toPosixFilePermissions((short) i) ));
  }

  private static Set<PosixFilePermission> toPosixFilePermissions(short mode) {
    Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);

    if ((mode & 0001) != 0) {
      result.add(PosixFilePermission.OTHERS_EXECUTE);
    }
    if ((mode & 0002) != 0) {
      result.add(PosixFilePermission.OTHERS_WRITE);
    }
    if ((mode & 0004) != 0) {
      result.add(PosixFilePermission.OTHERS_READ);
    }
    if ((mode & 0010) != 0) {
      result.add(PosixFilePermission.GROUP_EXECUTE);
    }
    if ((mode & 0020) != 0) {
      result.add(PosixFilePermission.GROUP_WRITE);
    }
    if ((mode & 0040) != 0) {
      result.add(PosixFilePermission.GROUP_READ);
    }
    if ((mode & 0100) != 0) {
      result.add(PosixFilePermission.OWNER_EXECUTE);
    }
    if ((mode & 0200) != 0) {
      result.add(PosixFilePermission.OWNER_WRITE);
    }
    if ((mode & 0400) != 0) {
      result.add(PosixFilePermission.OWNER_READ);
    }
    return result;
  }

  @ParameterizedTest(name =  "[{index}] mode: {0}")
  @MethodSource
  public void testToFsPermission(FsPermission expected, Set<PosixFilePermission> test) {
    assertThat(HadoopFileSystem.toFsPermission(test)).isEqualTo(expected);
  }

  private static Stream<Arguments> testToFsAction() {
    return IntStream.range(0, 0010)
        .mapToObj(i -> Arguments.of( toFsAction((short) i), toAccessModes((short) i) ));
  }

  private static FsAction toFsAction(short mode) {
    FsAction result = FsAction.NONE;

    if ((mode & 0001) != 0) {
      result = result.or(FsAction.EXECUTE);
    }
    if ((mode & 0002) != 0) {
      result = result.or(FsAction.WRITE);
    }
    if ((mode & 0004) != 0) {
      result = result.or(FsAction.READ);
    }
    return result;
  }

  private static Set<AccessMode> toAccessModes(short mode) {
    Set<AccessMode> result = EnumSet.noneOf(AccessMode.class);

    if ((mode & 0001) != 0) {
      result.add(AccessMode.EXECUTE);
    }
    if ((mode & 0002) != 0) {
      result.add(AccessMode.WRITE);
    }
    if ((mode & 0004) != 0) {
      result.add(AccessMode.READ);
    }
    return result;
  }

  @ParameterizedTest(name =  "[{index}] mode: {0}")
  @MethodSource
  public void testToFsAction(FsAction expected, Set<AccessMode> test) {
    assertThat(HadoopFileSystem.toFsAction(test)).isEqualTo(expected);
  }

  private static String tempFilePath;

  @TempDir
  static File tempFolder;

  @BeforeAll
  public static void createTempFile() throws Exception {
    tempFilePath = tempFolder.toPath().resolve(Paths.get("dremioFSReadTest.txt")).toString();
    File tempFile = Paths.get(tempFilePath).toFile();
    tempFile.createNewFile();

    // Write some data
    PrintWriter printWriter = new PrintWriter(tempFile);
    for (int i=1; i<=200000; i++) {
      printWriter.println (String.format("%d, key_%d", i, i));
    }
    printWriter.close();

    tempFilePath = tempFile.getPath();
    StatsCollectionEligibilityRegistrar.addSelf();
  }

  @Test
  public void testReadIOStats() throws Exception {
    FileSystem dfs = null;
    InputStream is = null;
    Configuration conf = new Configuration();
    OpProfileDef profileDef = new OpProfileDef(0 /*operatorId*/, UserBitShared.CoreOperatorType.PARQUET_ROW_GROUP_SCAN_VALUE /*operatorType*/, 0 /*inputCount*/);
    OperatorStats stats = new OperatorStats(profileDef, null /*allocator*/, 0);

    // start wait time method in OperatorStats expects the OperatorStats state to be in "processing"
    stats.startProcessing();

    try {
      dfs = HadoopFileSystem.get(org.apache.hadoop.fs.FileSystem.getLocal(conf), stats);
      is = dfs.open(Path.of(tempFilePath));

      byte[] buf = new byte[8000];
      while (is.read(buf, 0, buf.length) != -1) {
      }
    } finally {
      stats.stopProcessing();

      if (is != null) {
        is.close();
      }

      if (dfs != null) {
        dfs.close();
      }
    }

    OperatorProfile operatorProfile = stats.getProfile(true);
    assertThat(operatorProfile.getWaitNanos()).as("Expected wait time is non-zero, but got zero wait time").isGreaterThan(0);
    OperatorStats.IOStats ioStats = stats.getReadIOStats();
    long minIOReadTime = ioStats.minIOTime.longValue();
    long maxIOReadTime = ioStats.maxIOTime.longValue();
    long avgIOReadTime = ioStats.totalIOTime.longValue() / ioStats.numIO.get();
    long numIORead = ioStats.totalIOTime.get();

    OperatorStats.IOStats ioMetadataStats = stats.getMetadataReadIOStats();
    long minMetadataIOReadTime = ioMetadataStats.minIOTime.longValue();
    long maxMetadataIOReadTime = ioMetadataStats.maxIOTime.longValue();
    long avgMetadataIOReadTime = ioMetadataStats.totalIOTime.longValue() / ioMetadataStats.numIO.get();
    long numMetadataIORead = ioMetadataStats.totalIOTime.get();

    assertThat(minIOReadTime).isGreaterThan(0);
    assertThat(maxIOReadTime).isGreaterThan(0);
    assertThat(avgIOReadTime).isGreaterThan(0);
    assertThat(numIORead).isGreaterThan(0);
    assertThat(avgIOReadTime).isGreaterThanOrEqualTo(minIOReadTime).isLessThanOrEqualTo(maxIOReadTime);

    assertThat(ioStats.slowIOInfoList.size()).isGreaterThan(0);
    UserBitShared.SlowIOInfo slowIOInfo = ioStats.slowIOInfoList.get(0);
    assertThat(slowIOInfo.getFilePath()).isEqualTo(tempFilePath);
    assertThat(slowIOInfo.getIoTime()).isGreaterThanOrEqualTo(minIOReadTime).isLessThanOrEqualTo(maxIOReadTime);
    assertThat(slowIOInfo.getIoSize()).isGreaterThan(0);

    assertThat(minMetadataIOReadTime).isGreaterThan(0);
    assertThat(maxMetadataIOReadTime).isGreaterThan(0);
    assertThat(avgMetadataIOReadTime).isGreaterThan(0);
    assertThat(numMetadataIORead).isGreaterThan(0);
    assertThat(avgMetadataIOReadTime).isGreaterThanOrEqualTo(minMetadataIOReadTime).isLessThanOrEqualTo(maxMetadataIOReadTime);

    assertThat(ioMetadataStats.slowIOInfoList.size()).isGreaterThan(0);
    UserBitShared.SlowIOInfo slowMetadataIOInfo = ioMetadataStats.slowIOInfoList.get(0);
    assertThat(slowMetadataIOInfo.getFilePath()).isEqualTo(tempFilePath);
    assertThat(slowMetadataIOInfo.getIoTime()).isGreaterThanOrEqualTo(minMetadataIOReadTime).isLessThanOrEqualTo(maxMetadataIOReadTime);
  }

  @Test
  public void testWriteIOStats() throws Exception {
    FileSystem dfs = null;
    OutputStream os = null;
    Configuration conf = new Configuration();
    OpProfileDef profileDef = new OpProfileDef(0 /*operatorId*/, UserBitShared.CoreOperatorType.PARQUET_WRITER_VALUE /*operatorType*/, 0 /*inputCount*/);
    OperatorStats stats = new OperatorStats(profileDef, null /*allocator*/, 0);

    // start wait time method in OperatorStats expects the OperatorStats state to be in "processing"
    stats.startProcessing();

    try {
      dfs = HadoopFileSystem.get(org.apache.hadoop.fs.FileSystem.getLocal(conf), stats);
      os = dfs.create(Path.of(tempFolder.getPath()).resolve("dremioFSWriteTest.txt"));

      byte[] buf = new byte[8192];
      for (int i = 0; i < 10000; ++i) {
        os.write(buf);
      }
    } finally {
      if (os != null) {
        os.close();
      }

      stats.stopProcessing();

      if (dfs != null) {
        dfs.close();
      }
    }

    OperatorProfile operatorProfile = stats.getProfile(true);
    assertThat(operatorProfile.getWaitNanos()).as("Expected wait time is non-zero, but got zero wait time").isGreaterThan(0);
    OperatorStats.IOStats ioStats = stats.getWriteIOStats();
    long minIOWriteTime = ioStats.minIOTime.longValue();
    long maxIOWriteTime = ioStats.maxIOTime.longValue();
    long avgIOWriteTime = ioStats.totalIOTime.longValue() / ioStats.numIO.get();
    long numIOWrite = ioStats.totalIOTime.get();

    assertThat(minIOWriteTime).isGreaterThan(0);
    assertThat(maxIOWriteTime).isGreaterThan(0);
    assertThat(avgIOWriteTime).isGreaterThan(0);
    assertThat(avgIOWriteTime).isGreaterThanOrEqualTo(minIOWriteTime).isLessThanOrEqualTo(maxIOWriteTime);
    assertThat(numIOWrite).isGreaterThan(0);

    assertThat(ioStats.slowIOInfoList.size()).isGreaterThan(0);
    UserBitShared.SlowIOInfo slowIOInfo = ioStats.slowIOInfoList.get(0);
    assertThat(slowIOInfo.getIoSize()).isGreaterThan(0);
    assertThat(slowIOInfo.getIoTime()).isGreaterThanOrEqualTo(minIOWriteTime).isLessThanOrEqualTo(maxIOWriteTime);
    assertThat(slowIOInfo.getFilePath()).contains("dremioFSWriteTest.txt");
  }

  @Test
  public void testNoWaitTime() throws Exception {
    FileSystem dfs = null;
    InputStream is = null;
    Configuration conf = new Configuration();
    OpProfileDef profileDef = new OpProfileDef(0 /*operatorId*/, 0 /*operatorType*/, 0 /*inputCount*/);
    OperatorStats stats = new OperatorStats(profileDef, null /*allocator*/);

    // start wait time method in OperatorStats expects the OperatorStats state to be in "processing"
    stats.startProcessing();

    try {
      org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.getLocal(conf);
      dfs = HadoopFileSystem.get(fs, stats, true);
      Path path = Path.of(tempFilePath);
      org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(path.toURI());
      is = ((HadoopFileSystem) dfs).newFSDataInputStreamWrapper(Path.of(tempFilePath), fs.open(hadoopPath) , stats, false);

      byte[] buf = new byte[8000];
      while (is.read(buf, 0, buf.length) != -1) {
      }
    } finally {
      stats.stopProcessing();

      if (is != null) {
        is.close();
      }

      if (dfs != null) {
        dfs.close();
      }
    }

    assertThat(stats.getProfile().getWaitNanos()).as("Expected wait time is zero, but got non-zero wait time").isEqualTo(0);
  }

  @Test
  public void testHiddenFilesAreIgnored() throws IOException {
    Configuration conf = new Configuration();
    try (FileSystem dfs = HadoopFileSystem.getLocal(conf)) {

      final Path folderPath = Path.of(tempFolder.toString()).resolve("hidden_files_folders_test");
      createFolderWithContent(dfs, folderPath, 10, 20, 30);

      try(DirectoryStream<FileAttributes> iterable = FileSystemUtils.listRecursive(dfs, folderPath, NO_HIDDEN_FILES)) {
        assertEquals(10L, Iterables.size(iterable), "Should return 10 visible files");
      }

      dfs.delete(folderPath, true);
    }
  }

  @Test
  public void testHiddenFoldersAreIgnored() throws IOException {
    final Configuration conf = new Configuration();

    try (FileSystem dfs = HadoopFileSystem.getLocal(conf)) {
      final Path folderPath = Path.of(tempFolder.toString()).resolve("hidden_files_folders_test");
      // produces visible files
      createFolderWithContent(dfs, folderPath, 10, 1, 1);
      createFolderWithContent(dfs, folderPath.resolve("visible"), 3, 0, 0);
      // hidden files
      createFolderWithContent(dfs, folderPath.resolve("_hidden"), 10, 0, 0);
      createFolderWithContent(dfs, folderPath.resolve(".hidden"), 10, 0, 0);


      try(DirectoryStream<FileAttributes> directoryStream =  FileSystemUtils.listRecursive(dfs, folderPath, NO_HIDDEN_FILES)) {
        assertEquals(13L,
            getStream(directoryStream.iterator()).filter(f -> !f.isDirectory()).count(),
          "Should return 13 visible files");
      }
      dfs.delete(folderPath, true);
    }
  }

  @Test
  public void testListRecursiveOnFile() throws IOException {
    final Configuration conf = new Configuration();

    try (FileSystem dfs = HadoopFileSystem.getLocal(conf)) {
      final Path folderPath = Path.of(tempFolder.toString()).resolve("list_recursive_on_file");
      final Path foo = folderPath.resolve("foo.txt");
      dfs.create(foo);


      try(DirectoryStream<FileAttributes> directoryStream = FileSystemUtils.listRecursive(dfs, foo, ALL_FILES)) {
        List<FileAttributes> files = Lists.newArrayList(directoryStream);
        assertThat(files).hasSize(1);
        final FileAttributes attributes = files.get(0);
        assertThat(attributes.getPath().toURI().getPath()).isEqualTo(foo.toURI().getPath());
      }
    }
  }

  private void createFolderWithContent(FileSystem fs, Path parent, int visibleFilesCount,
    int hiddenGroup1Count, int hiddenGroup2Count) throws IOException {
    fs.mkdirs(parent);
    createFiles(fs, parent, "", visibleFilesCount);
    createFiles(fs, parent, ".", hiddenGroup1Count);
    createFiles(fs, parent, "_", hiddenGroup2Count);
  }

  private void createFiles(FileSystem fs, Path parentFolder, String filePrefix, int count) throws IOException {
    for (int i = 0; i < count; i++) {
      fs.create(parentFolder.resolve(String.format("%ssome_file_%s.txt", filePrefix, i)));
    }
  }

  private <T> Stream<T> getStream(Iterator<T> iterator) {
    final Iterable<T> iterable = () -> iterator;
    return StreamSupport.stream(iterable.spliterator(), false);
  }
}
