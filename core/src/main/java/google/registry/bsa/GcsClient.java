// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.bsa;

import static com.google.common.io.BaseEncoding.base16;

import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.api.Label;
import google.registry.bsa.api.NonBlockedDomain;
import google.registry.bsa.api.Order;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import javax.inject.Inject;

/** Stores and accesses BSA-related data, including original downloads and processed data. */
public class GcsClient {

  static final String LABELS_DIFF_FILE = "labels_diff.csv";
  public static final String DOMAINS_IN_USE_FILE = "domains_in_use.csv";
  public static final String ORDERS_DIFF_FILE = "orders_diff.csv";
  private final GcsUtils gcsUtils;
  private final String bucketName;

  private final String checksumAlgorithm;

  @Inject
  GcsClient(
      GcsUtils gcsUtils,
      @Config("bsaGcsBucket") String bucketName,
      @Config("bsaChecksumAlgorithm") String checksumAlgorithm) {
    this.gcsUtils = gcsUtils;
    this.bucketName = bucketName;
    this.checksumAlgorithm = checksumAlgorithm;
  }

  static String getBlockListFileName(BlockList blockList) {
    return blockList.name() + ".csv";
  }

  ImmutableMap<BlockList, String> saveAndChecksumBlockList(
      String jobName, ImmutableList<LazyBlockList> blockLists) {
    // Downloading sequentially, since one is expected to be much smaller than the other.
    return blockLists.stream()
        .collect(
            ImmutableMap.toImmutableMap(
                LazyBlockList::getName, blockList -> saveAndChecksumBlockList(jobName, blockList)));
  }

  private String saveAndChecksumBlockList(String jobName, LazyBlockList blockList) {
    BlobId blobId = getBlobId(jobName, getBlockListFileName(blockList.getName()));
    try (BufferedOutputStream gcsWriter =
        new BufferedOutputStream(gcsUtils.openOutputStream(blobId))) {
      MessageDigest messageDigest = MessageDigest.getInstance(checksumAlgorithm);
      blockList.consumeAll(
          (byteArray, length) -> {
            try {
              gcsWriter.write(byteArray, 0, length);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            messageDigest.update(byteArray, 0, length);
          });
      return base16().lowerCase().encode(messageDigest.digest());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private static void writeWithNewline(BufferedWriter writer, String line) {
    try {
      writer.write(line);
      if (!line.endsWith("\n")) {
        writer.write('\n');
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<String> readBlockList(String jobName, BlockList blockList) {
    return readStream(getBlobId(jobName, getBlockListFileName(blockList)));
  }

  Stream<Order> readOrderDiffs(String jobName) {
    BlobId blobId = getBlobId(jobName, ORDERS_DIFF_FILE);
    return readStream(blobId).map(Order::deserialize);
  }

  void writeOrderDiffs(String jobName, Stream<Order> orders) {
    BlobId blobId = getBlobId(jobName, ORDERS_DIFF_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      orders.map(Order::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<Label> readLabelDiffs(String jobName) {
    BlobId blobId = getBlobId(jobName, LABELS_DIFF_FILE);
    return readStream(blobId).map(Label::deserialize);
  }

  void writeLabelDiffs(String jobName, Stream<Label> labels) {
    BlobId blobId = getBlobId(jobName, LABELS_DIFF_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      labels.map(Label::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<NonBlockedDomain> readNonBlockedDomains(String jobName) {
    BlobId blobId = getBlobId(jobName, DOMAINS_IN_USE_FILE);
    return readStream(blobId).map(NonBlockedDomain::deserialize);
  }

  void writeNonBlockedDomains(String jobName, Stream<NonBlockedDomain> unblockables) {
    BlobId blobId = getBlobId(jobName, DOMAINS_IN_USE_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      unblockables
          .map(NonBlockedDomain::serialize)
          .forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  BlobId getBlobId(String folder, String name) {
    return BlobId.of(bucketName, String.format("%s/%s", folder, name));
  }

  Stream<String> readStream(BlobId blobId) {
    return new BufferedReader(
            new InputStreamReader(gcsUtils.openInputStream(blobId), StandardCharsets.UTF_8))
        .lines();
  }

  BufferedWriter getWriter(BlobId blobId) {
    return new BufferedWriter(
        new OutputStreamWriter(gcsUtils.openOutputStream(blobId), StandardCharsets.UTF_8));
  }
}
