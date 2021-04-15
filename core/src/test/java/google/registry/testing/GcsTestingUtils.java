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

package google.registry.testing;

import com.google.appengine.tools.cloudstorage.GcsFileOptions;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

/** Utility methods for testing Google Cloud Storage code. */
public final class GcsTestingUtils {

  /** Slurps a Cloud Storage file into memory. */
  public static byte[] readGcsFile(GcsService gcsService, GcsFilename file)
      throws IOException {
    try (InputStream input = Channels.newInputStream(gcsService.openReadChannel(file, 0))) {
      return ByteStreams.toByteArray(input);
    }
  }

  /** Writes a Cloud Storage file. */
  public static void writeGcsFile(GcsService gcsService, GcsFilename file, byte[] data)
      throws IOException {
    gcsService.createOrReplace(file, GcsFileOptions.getDefaultInstance(), ByteBuffer.wrap(data));
  }

  public static void deleteGcsFile(GcsService gcsService, GcsFilename file) throws IOException {
    gcsService.delete(file);
  }

  private GcsTestingUtils() {}
}
