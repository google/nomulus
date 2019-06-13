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

import static com.google.common.collect.Iterables.getLast;

import com.google.appengine.tools.cloudstorage.GcsFileMetadata;
import com.google.appengine.tools.cloudstorage.GcsFileOptions;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.ListOptions;
import com.google.appengine.tools.cloudstorage.ListResult;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import javax.annotation.CheckReturnValue;
import javax.inject.Inject;

/** Utilities for working with Google Cloud Storage. */
public class GcsUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableMap<String, MediaType> EXTENSIONS =
      new ImmutableMap.Builder<String, MediaType>()
          .put("ghostryde", MediaType.APPLICATION_BINARY)
          .put("length", MediaType.PLAIN_TEXT_UTF_8)
          .put("json", MediaType.JSON_UTF_8)
          .build();

  private final GcsService gcsService;
  private final int bufferSize;

  @Inject
  public GcsUtils(GcsService gcsService, @Config("gcsBufferSize") int bufferSize) {
    this.gcsService = gcsService;
    this.bufferSize = bufferSize;
  }

  /** Opens a GCS file for reading as an {@link InputStream} with prefetching. */
  @CheckReturnValue
  public InputStream openInputStream(GcsFilename filename) {
    return Channels.newInputStream(gcsService.openPrefetchingReadChannel(filename, 0, bufferSize));
  }

  /** Opens a GCS file for writing as an {@link OutputStream}, overwriting existing files. */
  @CheckReturnValue
  public OutputStream openOutputStream(GcsFilename filename) throws IOException {
    return Channels.newOutputStream(gcsService.createOrReplace(filename, getOptions(filename)));
  }

  /** Creates a GCS file with the given byte contents, overwriting existing files. */
  public void createFromBytes(GcsFilename filename, byte[] bytes) throws IOException {
    gcsService.createOrReplace(filename, getOptions(filename), ByteBuffer.wrap(bytes));
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
    ListResult result =
        gcsService.list(bucketName, new ListOptions.Builder().setPrefix(prefix).build());
    final ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
    result.forEachRemaining(
        listItem -> {
          if (!listItem.isDirectory()) {
            builder.add(listItem.getName().replaceFirst(prefix, ""));
          }
        });
    return builder.build();
  }

  /** Returns {@code true} if a file exists and is non-empty on Google Cloud Storage. */
  public boolean existsAndNotEmpty(GcsFilename file) {
    GcsFileMetadata metadata;
    try {
      metadata = gcsService.getMetadata(file);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to check if GCS file exists");
      return false;
    }
    return metadata != null && metadata.getLength() > 0;
  }

  /** Determines most appropriate {@link GcsFileOptions} based on filename extension. */
  private static GcsFileOptions getOptions(GcsFilename filename) {
    GcsFileOptions.Builder builder = new GcsFileOptions.Builder().cacheControl("no-cache");
    MediaType mediaType = EXTENSIONS.get(getLast(Splitter.on('.').split(filename.getObjectName())));
    if (mediaType != null) {
      builder = builder.mimeType(mediaType.type());
    }
    return builder.build();
  }
}
