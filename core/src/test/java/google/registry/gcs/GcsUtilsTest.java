// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.gcs;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;
import google.registry.testing.SystemPropertyExtension;
import google.registry.util.RegistryEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link GcsUtilsTest}. */
class GcsUtilsTest {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  private GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private String bucket = "my-bucket";
  private String filename = "my-file";
  private BlobId blobId = BlobId.of(bucket, filename);
  private ImmutableMap<String, String> metadata = ImmutableMap.of("key1", "val1", "Key2", "val2");
  private final byte[] bytes = new byte[] {'a', 'b', 'c'};
  private RegistryEnvironment previousEnvironment;

  @BeforeEach
  void beforeEach() {
    previousEnvironment = RegistryEnvironment.get();
    GcsUtils.clearPapCache();
  }

  @AfterEach
  void afterEach() {
    previousEnvironment.setup();
  }

  @Test
  void testSerialization_testStorage() throws Exception {
    assertThat(deserialize(serialize(gcsUtils))).isEqualTo(gcsUtils);
  }

  @Test
  void testSerialization_realStorage() throws Exception {
    gcsUtils = new GcsUtils(StorageOptions.getDefaultInstance());
    assertThat(deserialize(serialize(gcsUtils))).isEqualTo(gcsUtils);
  }

  @Test
  void testStreams() throws Exception {
    try (OutputStream os = gcsUtils.openOutputStream(blobId, metadata)) {
      os.write(bytes);
      os.flush();
    }
    assertThat(ByteStreams.toByteArray(gcsUtils.openInputStream(blobId))).isEqualTo(bytes);
    assertThat(gcsUtils.getBlobInfo(blobId).getMetadata()).containsExactlyEntriesIn(metadata);
  }

  @Test
  void testCreateListReadDelete() throws Exception {
    gcsUtils.createFromBytes(BlobInfo.newBuilder(blobId).setMetadata(metadata).build(), bytes);
    assertThat(gcsUtils.existsAndNotEmpty(blobId)).isTrue();
    assertThat(gcsUtils.listFolderObjects(bucket, "")).containsExactly("my-file");
    assertThat(gcsUtils.getBlobInfo(blobId).getMetadata()).isEqualTo(metadata);
    assertThat(gcsUtils.readBytesFrom(blobId)).isEqualTo(bytes);
    gcsUtils.delete(blobId);
    assertThat(gcsUtils.existsAndNotEmpty(blobId)).isFalse();
    assertThat(gcsUtils.listFolderObjects(bucket, "")).isEmpty();
  }

  @Test
  void testSetContentType() {
    blobId = BlobId.of(bucket, "something.json");
    gcsUtils.createFromBytes(blobId, bytes);
    assertThat(gcsUtils.getBlobInfo(blobId).getContentType())
        .isEqualTo(MediaType.JSON_UTF_8.toString());
  }

  @Test
  void testList() throws Exception {
    ImmutableList<BlobId> blobIds =
        ImmutableList.of(
            BlobId.of(bucket, "a/b/xyz.txt"),
            BlobId.of(bucket, "a/cde.exe"),
            BlobId.of(bucket, "fgh.jpg"),
            BlobId.of(bucket, "c/ijk.mp4"));

    for (BlobId blobId : blobIds) {
      gcsUtils.createFromBytes(blobId, bytes);
    }
    assertThat(gcsUtils.listFolderObjects(bucket, ""))
        .containsExactly("a/b/xyz.txt", "a/cde.exe", "fgh.jpg", "c/ijk.mp4");
    assertThat(gcsUtils.listFolderObjects(bucket, "a/")).containsExactly("b/xyz.txt", "cde.exe");
  }

  @Test
  void testEmptyFile() {
    gcsUtils.createFromBytes(blobId, new byte[] {});
    assertThat(gcsUtils.existsAndNotEmpty(blobId)).isFalse();
  }

  @Test
  void testVerifyPublicAccessPrevention_enforced() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    Storage mockStorage = mock(Storage.class);
    StorageOptions mockOptions = mock(StorageOptions.class);
    when(mockOptions.getService()).thenReturn(mockStorage);

    Bucket mockBucket = mock(Bucket.class);
    when(mockStorage.get("my-bucket")).thenReturn(mockBucket);

    BucketInfo.IamConfiguration mockIamConfig = mock(BucketInfo.IamConfiguration.class);
    when(mockBucket.getIamConfiguration()).thenReturn(mockIamConfig);
    when(mockIamConfig.getPublicAccessPrevention())
        .thenReturn(BucketInfo.PublicAccessPrevention.ENFORCED);

    GcsUtils utils = new GcsUtils(mockOptions);
    utils.verifyPublicAccessPrevention("my-bucket");
  }

  @Test
  void testVerifyPublicAccessPrevention_notCheckedInNonProd() {
    RegistryEnvironment.SANDBOX.setup(systemPropertyExtension);
    Storage mockStorage = mock(Storage.class);
    StorageOptions mockOptions = mock(StorageOptions.class);
    when(mockOptions.getService()).thenReturn(mockStorage);

    Bucket mockBucket = mock(Bucket.class);
    when(mockStorage.get("my-bucket")).thenReturn(mockBucket);

    BucketInfo.IamConfiguration mockIamConfig = mock(BucketInfo.IamConfiguration.class);
    when(mockBucket.getIamConfiguration()).thenReturn(mockIamConfig);
    when(mockIamConfig.getPublicAccessPrevention())
        .thenReturn(BucketInfo.PublicAccessPrevention.INHERITED);

    GcsUtils utils = new GcsUtils(mockOptions);
    // no exception thrown even though PAP isn't enforced
    utils.verifyPublicAccessPrevention("my-bucket");
  }

  @Test
  void testVerifyPublicAccessPrevention_notEnforced() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    Storage mockStorage = mock(Storage.class);
    StorageOptions mockOptions = mock(StorageOptions.class);
    when(mockOptions.getService()).thenReturn(mockStorage);

    Bucket mockBucket = mock(Bucket.class);
    when(mockStorage.get("my-bucket")).thenReturn(mockBucket);

    BucketInfo.IamConfiguration mockIamConfig = mock(BucketInfo.IamConfiguration.class);
    when(mockBucket.getIamConfiguration()).thenReturn(mockIamConfig);
    when(mockIamConfig.getPublicAccessPrevention())
        .thenReturn(BucketInfo.PublicAccessPrevention.INHERITED);

    GcsUtils utils = new GcsUtils(mockOptions);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> utils.verifyPublicAccessPrevention("my-bucket"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Public Access Prevention is not enforced on bucket my-bucket");
  }

  @Test
  void testVerifyPublicAccessPrevention_nonexistentBucket() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    Storage mockStorage = mock(Storage.class);
    StorageOptions mockOptions = mock(StorageOptions.class);
    when(mockOptions.getService()).thenReturn(mockStorage);

    when(mockStorage.get("nonexistent-bucket")).thenReturn(null);

    GcsUtils utils = new GcsUtils(mockOptions);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> utils.verifyPublicAccessPrevention("nonexistent-bucket"));
    assertThat(thrown).hasMessageThat().contains("Bucket nonexistent-bucket does not exist");
  }

  private static byte[] serialize(Object object) throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(object);
      oos.flush();
      return baos.toByteArray();
    }
  }

  private static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
      return ois.readObject();
    }
  }
}
