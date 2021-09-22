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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.util.GoogleCredentialsBundle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import javax.annotation.CheckReturnValue;
import javax.inject.Inject;

/**
 * Utilities for working with Google Cloud Storage.
 *
 * <p>It is {@link Serializable} so that it can be used in MapReduce or Beam.
 */
public class GcsUtils implements Serializable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableMap<String, MediaType> EXTENSIONS =
      new ImmutableMap.Builder<String, MediaType>()
          .put("ghostryde", MediaType.APPLICATION_BINARY)
          .put("length", MediaType.PLAIN_TEXT_UTF_8)
          .put("json", MediaType.JSON_UTF_8)
          .build();

  private final StorageOptions storageOptions;

  private Storage storage() {
    return storageOptions.getService();
  }

  @Inject
  public GcsUtils(@DefaultCredential GoogleCredentialsBundle credentialsBundle) {
    this(
        StorageOptions.newBuilder()
            .setCredentials(credentialsBundle.getGoogleCredentials())
            .build());
  }

  @VisibleForTesting
  public GcsUtils(StorageOptions storageOptions) {
    this.storageOptions = storageOptions;
  }

  /** Opens a GCS file for reading as an {@link InputStream}. */
  @CheckReturnValue
  public InputStream openInputStream(BlobId blobId) {
    return Channels.newInputStream(storage().reader(blobId));
  }

  /** Opens a GCS file for writing as an {@link OutputStream}, overwriting existing files. */
  @CheckReturnValue
  public OutputStream openOutputStream(BlobId blobId) {
    return Channels.newOutputStream(storage().writer(createBlobInfo(blobId)));
  }

  /**
   * Opens a GCS file for writing as an {@link OutputStream}, overwriting existing files and setting
   * the given metadata.
   */
  @CheckReturnValue
  public OutputStream openOutputStream(BlobId blobId, ImmutableMap<String, String> metadata) {
    return Channels.newOutputStream(
        storage().writer(BlobInfo.newBuilder(blobId).setMetadata(metadata).build()));
  }

  /** Creates a GCS file with the given byte contents, overwriting existing files. */
  public void createFromBytes(BlobId blobId, byte[] bytes) throws StorageException {
    createFromBytes(createBlobInfo(blobId), bytes);
  }

  /** Creates a GCS file with the given byte contents and metadata, overwriting existing files. */
  public void createFromBytes(BlobInfo blobInfo, byte[] bytes) throws StorageException {
    storage().create(blobInfo, bytes);
  }

  /** Read the content of the given GCS file and return it in a byte array. */
  public byte[] readBytesFrom(BlobId blobId) throws StorageException {
    return storage().readAllBytes(blobId);
  }

  /** Delete the given GCS file. */
  public void delete(BlobId blobId) throws StorageException {
    storage().delete(blobId);
  }

  /**
   * Returns a list of all object names within a bucket for a given prefix.
   *
   * <p>Note this also strips the provided prefix from the object, leaving only the object name
   * (i.e. if the bucket hello contains gs://hello/world/myobj.txt and gs://hello/world/a/b.csv,
   * then listFolderObjects("hello", "world/") would return {"myobj.txt", "a/b.csv"}.
   *
   * @param bucketName the name of the bucket, with no gs:// namespace or trailing slashes
   * @param prefix the string prefix all objects in the bucket should start with
   */
  public ImmutableList<String> listFolderObjects(String bucketName, String prefix)
      throws IOException {
    return Streams.stream(storage().list(bucketName, BlobListOption.prefix(prefix)).iterateAll())
        .map(blob -> blob.getName().substring(prefix.length()))
        .collect(toImmutableList());
  }

  /** Returns {@code true} if a file exists and is non-empty on Google Cloud Storage. */
  public boolean existsAndNotEmpty(BlobId blobId) {
    try {
      Blob blob = storage().get(blobId);
      return blob != null && blob.getSize() > 0;
    } catch (StorageException e) {
      logger.atWarning().withCause(e).log("Failure while checking if GCS file exists.");
      return false;
    }
  }

  /** Returns the user defined metadata of a GCS file if the file exists, or an empty map. */
  public ImmutableMap<String, String> getMetadata(BlobId blobId) throws StorageException {
    Blob blob = storage().get(blobId);
    return blob == null ? ImmutableMap.of() : ImmutableMap.copyOf(blob.getMetadata());
  }

  /**
   * Returns the {@link BlobInfo} of the given GCS file.
   *
   * <p>Note that a {@link Blob} is returned, but on the {@link BlobInfo} part of it is usable.
   */
  public BlobInfo getBlobInfo(BlobId blobId) throws StorageException {
    return storage().get(blobId);
  }

  /** Determines most appropriate {@link BlobInfo} based on filename extension. */
  private static BlobInfo createBlobInfo(BlobId blobId) {
    BlobInfo.Builder builder = BlobInfo.newBuilder(blobId).setCacheControl("no-cache");
    MediaType mediaType = EXTENSIONS.get(getLast(Splitter.on('.').split(blobId.getName())));
    if (mediaType != null) {
      builder = builder.setContentType(mediaType.toString());
    }
    return builder.build();
  }

  // These two methods are needed to check whether serialization is done correctly in tests.
  @Override
  public boolean equals(Object obj) {
    return obj instanceof GcsUtils && ((GcsUtils) obj).storageOptions.equals(storageOptions);
  }

  @Override
  public int hashCode() {
    return storageOptions.hashCode();
  }
}
