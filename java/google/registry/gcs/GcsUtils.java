// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import com.google.appengine.tools.cloudstorage.GcsFileOptions.Builder;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.config.ConfigModule.Config;
import google.registry.util.FormattingLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import javax.annotation.CheckReturnValue;
import javax.inject.Inject;

/** Utilities for working with Google Cloud Storage. */
public class GcsUtils {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

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

  /** Returns {@code true} if a file exists and is non-empty on Google Cloud Storage. */
  public boolean existsAndNotEmpty(GcsFilename file) {
    GcsFileMetadata metadata;
    try {
      metadata = gcsService.getMetadata(file);
    } catch (IOException e) {
      logger.warning(e, "Failed to check if GCS file exists");
      return false;
    }
    if (metadata == null) {
      return false;
    }
    return metadata.getLength() > 0;
  }

  /** Determines most appropriate {@link GcsFileOptions} based on filename extension. */
  private static GcsFileOptions getOptions(GcsFilename filename) {
    Builder builder = new GcsFileOptions.Builder().cacheControl("no-cache");
    MediaType mediaType = EXTENSIONS.get(getLast(Splitter.on('.').split(filename.getObjectName())));
    if (mediaType != null) {
      builder = builder.mimeType(mediaType.type());
    }
    return builder.build();
  }
}
