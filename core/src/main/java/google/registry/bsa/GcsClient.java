package google.registry.bsa;

import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.common.Label;
import google.registry.bsa.common.Order;
import google.registry.bsa.common.UnblockableDomain;
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

class GcsClient {

  private final GcsUtils gcsUtils;
  private final String bucketName;

  @Inject
  GcsClient(GcsUtils gcsUtils, String bucketName) {
    this.gcsUtils = gcsUtils;
    this.bucketName = bucketName;
  }

  static String getBlockListFileName(BlockList blockList) {
    return blockList.name() + ".csv";
  }

  static String getLabelDiffFileName() {
    return "labels_diff.csv";
  }

  static String getUnblockableDomainsFileName() {
    return "unblockable_domains.csv";
  }

  static String getOrderDiffFileName() {
    return "orders_diff.csv";
  }

  ImmutableMap<BlockList, String> saveAndChecksumBlockList(
      long jobId, ImmutableList<LazyBlockList> blockLists) {
    // Downloading sequentially, since one is expected to be much smaller than the other.
    return blockLists.stream()
        .collect(
            ImmutableMap.toImmutableMap(
                LazyBlockList::getName, blockList -> saveAndChecksumBlockList(jobId, blockList)));
  }

  private String saveAndChecksumBlockList(long jobId, LazyBlockList blockList) {
    BlobId blobId = getBlobId(jobId, getBlockListFileName(blockList.getName()));
    try (BufferedOutputStream gcsWriter =
        new BufferedOutputStream(gcsUtils.openOutputStream(blobId))) {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA256");
      blockList.consumeAll(
          (byteArray, length) -> {
            try {
              gcsWriter.write(byteArray, 0, length);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            messageDigest.update(byteArray, 0, length);
          });
      return new String(messageDigest.digest(), StandardCharsets.UTF_8);
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

  Stream<String> readBlockList(long jobId, BlockList blockList) {
    return readStream(getBlobId(jobId, getBlockListFileName(blockList)));
  }

  Stream<Order> readOrderDiffs(long jobId) {
    BlobId blobId = getBlobId(jobId, getOrderDiffFileName());
    return readStream(blobId).map(Order::deserialize);
  }

  void writeOrderDiffs(long jobId, Stream<Order> orders) {
    BlobId blobId = getBlobId(jobId, getOrderDiffFileName());
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      orders.map(Order::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<Label> readLabelDiffs(long jobId) {
    BlobId blobId = getBlobId(jobId, getLabelDiffFileName());
    return readStream(blobId).map(Label::deserialize);
  }

  void writeLabelDiffs(long jobId, Stream<Label> labels) {
    BlobId blobId = getBlobId(jobId, getLabelDiffFileName());
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      labels.map(Label::serialize).forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  Stream<UnblockableDomain> readUnblockableDomains(long jobId) {
    BlobId blobId = getBlobId(jobId, getUnblockableDomainsFileName());
    return readStream(blobId).map(UnblockableDomain::deserialize);
  }

  void writeUnblockableDomains(long jobId, Stream<UnblockableDomain> unblockables) {
    BlobId blobId = getBlobId(jobId, getUnblockableDomainsFileName());
    try (BufferedWriter gcsWriter = getWriter(blobId)) {
      unblockables
          .map(UnblockableDomain::serialize)
          .forEach(line -> writeWithNewline(gcsWriter, line));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  BlobId getBlobId(long jobId, String name) {
    String folder = String.valueOf(jobId);
    return BlobId.of(folder, String.format("%s/%s", folder, name));
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
