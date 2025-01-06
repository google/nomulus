// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockOrder;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomainChange;
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

  // Intermediate data files:
  static final String LABELS_DIFF_FILE = "labels_diff.csv";
  static final String ORDERS_DIFF_FILE = "orders_diff.csv";
  static final String UNBLOCKABLE_DOMAINS_FILE = "unblockable_domains.csv";
  static final String REFRESHED_UNBLOCKABLE_DOMAINS_FILE = "refreshed_unblockable_domains.csv";

  // Logged report data sent to BSA.
  static final String IN_PROGRESS_ORDERS_REPORT = "in_progress_orders.json";
  static final String COMPLETED_ORDERS_REPORT = "completed_orders.json";
  static final String ADDED_UNBLOCKABLE_DOMAINS_REPORT = "added_unblockable_domains.json";
  static final String REMOVED_UNBLOCKABLE_DOMAINS_REPORT = "removed_unblockable_domains.json";

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

  static String getBlockListFileName(BlockListType blockListType) {
    return blockListType.name() + ".csv";
  }

  ImmutableMap<BlockListType, String> saveAndChecksumBlockList(
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

  Stream<String> readBlockList(String jobName, BlockListType blockListType) {
    return readStream(getBlobId(jobName, getBlockListFileName(blockListType)));
  }

  Stream<BlockOrder> readOrderDiffs(String jobName) {
    BlobId blobId = getBlobId(jobName, ORDERS_DIFF_FILE);
    return readStream(blobId).map(BlockOrder::deserialize);
  }

  void writeOrderDiffs(String jobName, Stream<BlockOrder> orders) {
    BlobId blobId = getBlobId(jobName, ORDERS_DIFF_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      orders.map(BlockOrder::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<BlockLabel> readLabelDiffs(String jobName) {
    BlobId blobId = getBlobId(jobName, LABELS_DIFF_FILE);
    return readStream(blobId).map(BlockLabel::deserialize);
  }

  void writeLabelDiffs(String jobName, Stream<BlockLabel> labels) {
    BlobId blobId = getBlobId(jobName, LABELS_DIFF_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      labels.map(BlockLabel::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<UnblockableDomain> readUnblockableDomains(String jobName) {
    BlobId blobId = getBlobId(jobName, UNBLOCKABLE_DOMAINS_FILE);
    return readStream(blobId).map(UnblockableDomain::deserialize);
  }

  void writeUnblockableDomains(String jobName, Stream<UnblockableDomain> unblockables) {
    BlobId blobId = getBlobId(jobName, UNBLOCKABLE_DOMAINS_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      unblockables
          .map(UnblockableDomain::serialize)
          .forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<UnblockableDomainChange> readRefreshChanges(String jobName) {
    BlobId blobId = getBlobId(jobName, REFRESHED_UNBLOCKABLE_DOMAINS_FILE);
    return readStream(blobId).map(UnblockableDomainChange::deserialize);
  }

  void writeRefreshChanges(String jobName, Stream<UnblockableDomainChange> changes) {
    BlobId blobId = getBlobId(jobName, REFRESHED_UNBLOCKABLE_DOMAINS_FILE);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      changes
          .map(UnblockableDomainChange::serialize)
          .forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  void logInProgressOrderReport(String jobName, Stream<String> lines) {
    BlobId blobId = getBlobId(jobName, IN_PROGRESS_ORDERS_REPORT);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      lines.forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  void logCompletedOrderReport(String jobName, Stream<String> lines) {
    BlobId blobId = getBlobId(jobName, COMPLETED_ORDERS_REPORT);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      lines.forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  void logAddedUnblockableDomainsReport(String jobName, Stream<String> lines) {
    BlobId blobId = getBlobId(jobName, ADDED_UNBLOCKABLE_DOMAINS_REPORT);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      lines.forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  void logRemovedUnblockableDomainsReport(String jobName, Stream<String> lines) {
    BlobId blobId = getBlobId(jobName, REMOVED_UNBLOCKABLE_DOMAINS_REPORT);
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      lines.forEach(line -> writeWithNewline(gcsWriter, line));
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
