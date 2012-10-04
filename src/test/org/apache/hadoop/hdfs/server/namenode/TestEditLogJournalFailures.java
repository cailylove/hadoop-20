/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.JournalSet.JournalAndStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.verification.api.VerificationMode;
import org.mortbay.log.Log;

public class TestEditLogJournalFailures {

  private int editsPerformed = 0;
  private MiniDFSCluster cluster;
  private FileSystem fs;
  private Runtime runtime;

  /**
   * Create the mini cluster for testing and sub in a custom runtime so that
   * edit log journal failures don't actually cause the JVM to exit.
   */
  @Before
  public void setUpMiniCluster() throws IOException {
    setUpMiniCluster(new Configuration(), true);
  }
  
  public void setUpMiniCluster(Configuration conf, boolean manageNameDfsDirs)
      throws IOException {
    cluster = new MiniDFSCluster(conf, 0, 
        true, null);
    cluster.waitActive();
    fs = cluster.getFileSystem();
    
    runtime = Runtime.getRuntime();
    runtime = spy(runtime);
    doNothing().when(runtime).exit(anyInt());
    
    FSEditLog.setRuntimeForTesting(runtime);
  }
  
  @After
  public void shutDownMiniCluster() throws IOException {
    if (fs != null)
      fs.close();
    if (cluster != null)
      cluster.shutdown();
  }
   
  @Test
  public void testSingleFailedEditsDirOnFlush() throws IOException {
    assertTrue(doAnEdit());
    // Invalidate one edits journal.
    invalidateEditsDirAtIndex(0, true, false);
    // Make sure runtime.exit(...) hasn't been called at all yet.
    assertExitInvocations(0);
    assertTrue(doAnEdit());
    // A single journal failure should not result in a call to runtime.exit(...).
    assertExitInvocations(0);
    assertFalse(cluster.getNameNode().isInSafeMode());
  }
   
  @Test
  public void testAllEditsDirsFailOnFlush() throws IOException {
    assertTrue(doAnEdit());
    // Invalidate both edits journals.
    invalidateEditsDirAtIndex(0, true, false);
    invalidateEditsDirAtIndex(1, true, false);
    // Make sure runtime.exit(...) hasn't been called at all yet.
    assertExitInvocations(0);
    assertTrue(doAnEdit());
    // The previous edit could not be synced to any persistent storage, should
    // have halted the NN.
    assertExitInvocations(1);
  }
  
  @Test
  public void testAllEditsDirFailOnWrite() throws IOException {
    assertTrue(doAnEdit());
    // Invalidate both edits journals.
    invalidateEditsDirAtIndex(0, true, true);
    invalidateEditsDirAtIndex(1, true, true);
    // Make sure runtime.exit(...) hasn't been called at all yet.
    assertExitInvocations(0);
    assertTrue(doAnEdit());
    // The previous edit could not be synced to any persistent storage, should
    // have halted the NN.
    assertExitInvocations(atLeast(1));
  }
  
  @Test
  public void testSingleFailedEditsDirOnSetReadyToFlush() throws IOException {
    assertTrue(doAnEdit());
    // Invalidate one edits journal.
    invalidateEditsDirAtIndex(0, false, false);
    // Make sure runtime.exit(...) hasn't been called at all yet.
    assertExitInvocations(0);
    assertTrue(doAnEdit());
    // A single journal failure should not result in a call to runtime.exit(...).
    assertExitInvocations(0);
    assertFalse(cluster.getNameNode().isInSafeMode());
  }
  
  @Test
  public void testSingleRequiredFailedEditsDirOnSetReadyToFlush()
      throws IOException {
    // Set one of the edits dirs to be required.
    File[] editsDirs = cluster.getNameEditsDirs().toArray(new File[0]);
    shutDownMiniCluster();
    Configuration conf = new Configuration();
    conf.set("dfs.name.edits.dir.required", editsDirs[1].toString());
    conf.setInt("dfs.name.edits.dir.minimum", 0);
    setUpMiniCluster(conf, true);
    
    assertTrue(doAnEdit());
    // Invalidated the one required edits journal.
    invalidateEditsDirAtIndex(1, false, false);
    // Make sure runtime.exit(...) hasn't been called at all yet.
    assertExitInvocations(0);
    
    // This will actually return true in the tests, since the NN will not in
    // fact call Runtime.exit();
    doAnEdit();
    
    // A single failure of a required journal should result in a call to
    // runtime.exit(...).
    assertExitInvocations(atLeast(1));
  }
  
  @Test
  public void testMultipleRedundantFailedEditsDirOnSetReadyToFlush()
      throws IOException {
    // Set up 4 name/edits dirs.
    shutDownMiniCluster();
    Configuration conf = new Configuration();
    String[] nameDirs = new String[4];
    for (int i = 0; i < nameDirs.length; i++) {
      File nameDir = new File(System.getProperty("test.build.data"),
          "name-dir" + i);
      nameDir.mkdirs();
      nameDirs[i] = nameDir.getAbsolutePath();
    }

    conf.set("dfs.name.edits.dir",
        StringUtils.join(nameDirs, ","));
    
    // Keep running unless there are less than 2 edits dirs remaining.
    conf.setInt("dfs.name.edits.dir.minimum", 2);
    
    setUpMiniCluster(conf, false);
    
    // All journals active.
    assertTrue(doAnEdit());
    assertExitInvocations(0);
    
    // Invalidate 1/4 of the redundant journals.
    invalidateEditsDirAtIndex(0, false, false);
    assertTrue(doAnEdit());
    assertExitInvocations(0);

    // Invalidate 2/4 of the redundant journals.
    invalidateEditsDirAtIndex(1, false, false);
    assertTrue(doAnEdit());
    assertExitInvocations(0);
    
    // Invalidate 3/4 of the redundant journals.
    invalidateEditsDirAtIndex(2, false, false);
    
    // This will actually return true in the tests, since the NN will not in
    // fact call Runtime.exit();
    doAnEdit();
    
    // A failure of more than the minimum number of redundant journals should
    // result in a call to runtime.exit(...).
    assertExitInvocations(atLeast(1));
  }

  /**
   * Replace the journal at index <code>index</code> with one that throws an
   * exception on flush.
   * 
   * @param index the index of the journal to take offline.
   * @return the original <code>EditLogOutputStream</code> of the journal.
   */
  private EditLogOutputStream invalidateEditsDirAtIndex(int index,
      boolean failOnFlush, boolean failOnWrite) throws IOException {
    FSImage fsimage = cluster.getNameNode().getFSImage();
    FSEditLog editLog = fsimage.getEditLog();

    JournalAndStream jas = editLog.getJournals().get(index);
    EditLogFileOutputStream elos =
      (EditLogFileOutputStream) jas.getCurrentStream();
    EditLogFileOutputStream spyElos = spy(elos);
    if (failOnWrite) {
      doThrow(new IOException("fail on write()")).when(spyElos).write(
          (FSEditLogOp) any());
    }
    if (failOnFlush) {
      doThrow(new IOException("fail on flush()")).when(spyElos).flush();
    } else {
      doThrow(new IOException("fail on setReadyToFlush()")).when(spyElos)
        .setReadyToFlush();
    }
    doNothing().when(spyElos).abort();
     
    jas.setCurrentStreamForTests(spyElos);
     
    return elos;
  }

  /**
   * Restore the journal at index <code>index</code> with the passed
   * {@link EditLogOutputStream}.
   * 
   * @param index index of the journal to restore.
   * @param elos the {@link EditLogOutputStream} to put at that index.
   */
  private void restoreEditsDirAtIndex(int index, EditLogOutputStream elos) {
    FSImage fsimage = cluster.getNameNode().getFSImage();
    FSEditLog editLog = fsimage.getEditLog();

    JournalAndStream jas = editLog.getJournals().get(index);
    jas.setCurrentStreamForTests(elos);
  }

  /**
   * Do a mutative metadata operation on the file system.
   * 
   * @return true if the operation was successful, false otherwise.
   */
  private boolean doAnEdit() throws IOException {
    return fs.mkdirs(new Path("/tmp", Integer.toString(editsPerformed++)));
  }
  
  /**
   * Make sure that Runtime.exit(...) has been called exactly
   * <code>expectedExits<code> number of times.
   * 
   * @param expectedExits the exact number of times Runtime.exit(...) should
   *                      have been called.
   */
  private void assertExitInvocations(int expectedExits) {
    assertExitInvocations(times(expectedExits));
  }

  /**
   * Make sure that Runtime.exit(...) has been called
   * <code>expectedExits<code> number of times.
   * 
   * @param expectedExits the number of times Runtime.exit(...) should have been called.
   */
  private void assertExitInvocations(VerificationMode expectedExits) {
    verify(runtime, expectedExits).exit(anyInt());
  }
}
