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

package org.apache.hadoop.raid;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Progressable;

/**
 * Represents a generic encoder that can generate a parity file for a source
 * file.
 */
public class Encoder {
  public static final Log LOG = LogFactory.getLog(
                                  "org.apache.hadoop.raid.Encoder");
  public static final int DEFAULT_PARALLELISM = 4;
  protected Configuration conf;
  protected int parallelism;
  protected Codec codec;
  protected ErasureCode code;
  protected Random rand;
  protected int bufSize;
  protected byte[][] readBufs;
  protected byte[][] writeBufs;

  /**
   * A class that acts as a sink for data, similar to /dev/null.
   */
  static class NullOutputStream extends OutputStream {
    public void write(byte[] b) throws IOException {}
    public void write(int b) throws IOException {}
    public void write(byte[] b, int off, int len) throws IOException {}
  }

  Encoder(Configuration conf, Codec codec) {
    this.conf = conf;
    this.parallelism = conf.getInt("raid.encoder.parallelism",
                                   DEFAULT_PARALLELISM);
    this.codec = codec;
    this.code = codec.createErasureCode(conf);
    this.rand = new Random();
    this.bufSize = conf.getInt("raid.encoder.bufsize", 1024 * 1024);
    this.writeBufs = new byte[codec.parityLength][];
    allocateBuffers();
  }

  private void allocateBuffers() {
    for (int i = 0; i < codec.parityLength; i++) {
      writeBufs[i] = new byte[bufSize];
    }
  }

  private void configureBuffers(long blockSize) {
    if ((long)bufSize > blockSize) {
      bufSize = (int)blockSize;
      allocateBuffers();
    } else if (blockSize % bufSize != 0) {
      bufSize = (int)(blockSize / 256L); // heuristic.
      if (bufSize == 0) {
        bufSize = 1024;
      }
      bufSize = Math.min(bufSize, 1024 * 1024);
      allocateBuffers();
    }
  }

  /**
   * The interface to use to generate a parity file.
   * This method can be called multiple times with the same Encoder object,
   * thus allowing reuse of the buffers allocated by the Encoder object.
   *
   * @param fs The filesystem containing the source file.
   * @param srcFile The source file.
   * @param parityFile The parity file to be generated.
   */
  public void encodeFile(
    FileSystem fs, Path srcFile, FileSystem parityFs, Path parityFile,
    short parityRepl, Progressable reporter) throws IOException {
    FileStatus srcStat = fs.getFileStatus(srcFile);
    long srcSize = srcStat.getLen();
    long blockSize = srcStat.getBlockSize();
    long numBlocks = (srcSize % blockSize == 0) ?
                      (srcSize / blockSize) :
                      ((srcSize / blockSize) + 1);
    long numStripes = (numBlocks % codec.stripeLength == 0) ?
                      (numBlocks / codec.stripeLength) :
                      ((numBlocks / codec.stripeLength) + 1);
    long expectedParityBlocks = numStripes * codec.parityLength;
    long expectedParityFileSize = numStripes * blockSize * codec.parityLength;

    // Create a tmp file to which we will write first.
    Path tmpDir = new Path(codec.tmpParityDirectory);
    if (!parityFs.mkdirs(tmpDir)) {
      throw new IOException("Could not create tmp dir " + tmpDir);
    }
    Path parityTmp = new Path(tmpDir,
                            parityFile.getName() + rand.nextLong());
    // Writing out a large parity file at replication 1 is difficult since
    // some datanode could die and we would not be able to close() the file.
    // So write at replication 2 and then reduce it after close() succeeds.
    short tmpRepl = parityRepl;
    if (expectedParityBlocks >=
        conf.getInt("raid.encoder.largeparity.blocks", 20)) {
      if (parityRepl == 1) {
        tmpRepl = 2;
      }
    }
    FSDataOutputStream out = parityFs.create(
                               parityTmp,
                               true,
                               conf.getInt("io.file.buffer.size", 64 * 1024),
                               tmpRepl,
                               blockSize);

    try {
      encodeFileToStream(fs, srcFile, srcSize, blockSize, out, reporter);
      out.close();
      out = null;
      LOG.info("Wrote temp parity file " + parityTmp);
      FileStatus tmpStat = parityFs.getFileStatus(parityTmp);
      if (tmpStat.getLen() != expectedParityFileSize) {
        throw new IOException("Expected parity size " + expectedParityFileSize +
          " does not match actual " + tmpStat.getLen());
      }

      // delete destination if exists
      if (parityFs.exists(parityFile)){
        parityFs.delete(parityFile, false);
      }
      parityFs.mkdirs(parityFile.getParent());
      if (tmpRepl > parityRepl) {
        parityFs.setReplication(parityTmp, parityRepl);
      }
      if (!parityFs.rename(parityTmp, parityFile)) {
        String msg = "Unable to rename file " + parityTmp + " to " + parityFile;
        throw new IOException (msg);
      }
      LOG.info("Wrote parity file " + parityFile);
    } finally {
      try {
        if (out != null) {
          out.close();
        }
      } finally {
        parityFs.delete(parityTmp, false);
      }
    }
  }

  /**
   * Recovers a corrupt block in a parity file to a local file.
   *
   * The encoder generates codec.parityLength parity blocks for a source file stripe.
   * Since we want only one of the parity blocks, this function creates
   * null outputs for the blocks to be discarded.
   *
   * @param fs The filesystem in which both srcFile and parityFile reside.
   * @param srcFile The source file.
   * @param srcSize The size of the source file.
   * @param blockSize The block size for the source/parity files.
   * @param corruptOffset The location of corruption in the parity file.
   * @param localBlockFile The destination for the reovered block.
   * @param progress A reporter for progress.
   */
  public void recoverParityBlockToFile(
    FileSystem fs,
    Path srcFile, long srcSize, long blockSize,
    Path parityFile, long corruptOffset,
    File localBlockFile, Progressable progress) throws IOException {
    OutputStream out = new FileOutputStream(localBlockFile);
    try {
      recoverParityBlockToStream(fs, srcFile, srcSize, blockSize, parityFile,
        corruptOffset, out, progress);
    } finally {
      out.close();
    }
  }

  /**
   * Recovers a corrupt block in a parity file to a local file.
   *
   * The encoder generates codec.parityLength parity blocks for a source file stripe.
   * Since we want only one of the parity blocks, this function creates
   * null outputs for the blocks to be discarded.
   *
   * @param fs The filesystem in which both srcFile and parityFile reside.
   * @param srcFile The source file.
   * @param srcSize The size of the source file.
   * @param blockSize The block size for the source/parity files.
   * @param corruptOffset The location of corruption in the parity file.
   * @param out The destination for the reovered block.
   * @param progress A reporter for progress.
   */
  public void recoverParityBlockToStream(
    FileSystem fs,
    Path srcFile, long srcSize, long blockSize,
    Path parityFile, long corruptOffset,
    OutputStream out, Progressable progress) throws IOException {
    LOG.info("Recovering parity block" + parityFile + ":" + corruptOffset);
    // Get the start offset of the corrupt block.
    corruptOffset = (corruptOffset / blockSize) * blockSize;
    // Output streams to each block in the parity file stripe.
    OutputStream[] outs = new OutputStream[codec.parityLength];
    long indexOfCorruptBlockInParityStripe =
      (corruptOffset / blockSize) % codec.parityLength;
    LOG.info("Index of corrupt block in parity stripe: " +
              indexOfCorruptBlockInParityStripe);
    // Create a real output stream for the block we want to recover,
    // and create null streams for the rest.
    for (int i = 0; i < codec.parityLength; i++) {
      if (indexOfCorruptBlockInParityStripe == i) {
        outs[i] = out;
      } else {
        outs[i] = new NullOutputStream();
      }
    }
    // Get the stripe index and start offset of stripe.
    long stripeIdx = corruptOffset / (codec.parityLength * blockSize);
    long stripeStart = stripeIdx * blockSize * codec.stripeLength;

    // Get input streams to each block in the source file stripe.
    InputStream[] blocks = stripeInputs(fs, srcFile, stripeStart,
        srcSize, blockSize);
    LOG.info("Starting recovery by using source stripe " +
              srcFile + ":" + stripeStart);
    try {
      // Read the data from the blocks and write to the parity file.
      encodeStripe(blocks, stripeStart, blockSize, outs, progress);
    } finally {
      RaidUtils.closeStreams(blocks);
    }
  }

  /**
   * Recovers a corrupt block in a parity file to an output stream.
   *
   * The encoder generates codec.parityLength parity blocks for a source file stripe.
   * Since there is only one output provided, some blocks are written out to
   * files before being written out to the output.
   *
   * @param fs The filesystem in which both srcFile and parityFile reside.
   * @param srcFile The source file.
   * @param srcSize The size of the source file.
   * @param blockSize The block size for the source/parity files.
   * @param out The destination for the reovered block.
   */
  private void encodeFileToStream(FileSystem fs, Path srcFile, long srcSize,
    long blockSize, OutputStream out, Progressable reporter) throws IOException {
    OutputStream[] tmpOuts = new OutputStream[codec.parityLength];
    // One parity block can be written directly to out, rest to local files.
    tmpOuts[0] = out;
    File[] tmpFiles = new File[codec.parityLength - 1];
    for (int i = 0; i < codec.parityLength - 1; i++) {
      tmpFiles[i] = File.createTempFile("parity", "_" + i);
      LOG.info("Created tmp file " + tmpFiles[i]);
      tmpFiles[i].deleteOnExit();
    }
    try {
      // Loop over stripes in the file.
      for (long stripeStart = 0; stripeStart < srcSize;
          stripeStart += blockSize * codec.stripeLength) {
        reporter.progress();
        LOG.info("Starting encoding of stripe " + srcFile + ":" + stripeStart);
        // Create input streams for blocks in the stripe.
        InputStream[] blocks = stripeInputs(fs, srcFile, stripeStart,
          srcSize, blockSize);
        try {
          // Create output streams to the temp files.
          for (int i = 0; i < codec.parityLength - 1; i++) {
            tmpOuts[i + 1] = new FileOutputStream(tmpFiles[i]);
          }
          // Call the implementation of encoding.
          encodeStripe(blocks, stripeStart, blockSize, tmpOuts, reporter);
        } finally {
          RaidUtils.closeStreams(blocks);
        }
        // Close output streams to the temp files and write the temp files
        // to the output provided.
        for (int i = 0; i < codec.parityLength - 1; i++) {
          tmpOuts[i + 1].close();
          tmpOuts[i + 1] = null;
          InputStream in  = new FileInputStream(tmpFiles[i]);
          RaidUtils.copyBytes(in, out, writeBufs[i], blockSize);
          reporter.progress();
        }
      }
    } finally {
      for (int i = 0; i < codec.parityLength - 1; i++) {
        if (tmpOuts[i + 1] != null) {
          tmpOuts[i + 1].close();
        }
        tmpFiles[i].delete();
        LOG.info("Deleted tmp file " + tmpFiles[i]);
      }
    }
  }

  /**
   * Return input streams for each block in a source file's stripe.
   * @param fs The filesystem where the file resides.
   * @param srcFile The source file.
   * @param stripeStartOffset The start offset of the stripe.
   * @param srcSize The size of the source file.
   * @param blockSize The block size for the source file.
   */
  protected InputStream[] stripeInputs(
    FileSystem fs,
    Path srcFile,
    long stripeStartOffset,
    long srcSize,
    long blockSize
    ) throws IOException {
    InputStream[] blocks = new InputStream[codec.stripeLength];
    try {
      for (int i = 0; i < codec.stripeLength; i++) {
        long seekOffset = stripeStartOffset + i * blockSize;
        if (seekOffset < srcSize) {
          FSDataInputStream in = fs.open(
                     srcFile, conf.getInt("io.file.buffer.size", 64 * 1024));
          in.seek(seekOffset);
          LOG.info("Opening stream at " + srcFile + ":" + seekOffset);
          blocks[i] = in;
        } else {
          LOG.info("Using zeros at offset " + seekOffset);
          // We have no src data at this offset.
          blocks[i] = new RaidUtils.ZeroInputStream(
                            seekOffset + blockSize);
        }
      }
      return blocks;
    } catch (IOException e) {
      // If there is an error during opening a stream, close the previously
      // opened streams and re-throw.
      RaidUtils.closeStreams(blocks);
      throw e;
    }
  }

  /**
   * Wraps around encodeStripeImpl in order to configure buffers.
   * Having buffers of the right size is extremely important. If the the
   * buffer size is not a divisor of the block size, we may end up reading
   * across block boundaries.
   */
  void encodeStripe(
    InputStream[] blocks,
    long stripeStartOffset,
    long blockSize,
    OutputStream[] outs,
    Progressable reporter) throws IOException {
    configureBuffers(blockSize);
    int boundedBufferCapacity = 1;
    ParallelStreamReader parallelReader = new ParallelStreamReader(
      reporter, blocks, bufSize, parallelism, boundedBufferCapacity, blockSize);
    parallelReader.start();
    try {
      for (long encoded = 0; encoded < blockSize; encoded += bufSize) {
        ParallelStreamReader.ReadResult readResult = null;
        try {
          readResult = parallelReader.getReadResult();
        } catch (InterruptedException e) {
          throw new IOException("Interrupted while waiting for read result");
        }
        // Cannot tolerate any IO errors.
        IOException readEx = readResult.getException();
        if (readEx != null) {
          throw readEx;
        }

        code.encodeBulk(readResult.readBufs, writeBufs);
        reporter.progress();

        // Now that we have some data to write, send it to the temp files.
        for (int i = 0; i < codec.parityLength; i++) {
          outs[i].write(writeBufs[i], 0, bufSize);
          reporter.progress();
        }
      }
    } finally {
      parallelReader.shutdown();
    }
  }
}
