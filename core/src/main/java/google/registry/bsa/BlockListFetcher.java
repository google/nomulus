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

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.HttpMethods;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.bsa.api.BsaCredential;
import google.registry.bsa.api.BsaException;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.UrlConnectionService;
import google.registry.util.Retrier;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

/** Fetches data from the BSA API. */
public class BlockListFetcher {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final UrlConnectionService urlConnectionService;
  private final BsaCredential credential;

  private final ImmutableMap<String, String> blockListUrls;
  private final Retrier retrier;

  @Inject
  BlockListFetcher(
      UrlConnectionService urlConnectionService,
      BsaCredential credential,
      @Config("bsaDataUrls") ImmutableMap<String, String> blockListUrls,
      Retrier retrier) {
    this.urlConnectionService = urlConnectionService;
    this.credential = credential;
    this.blockListUrls = blockListUrls;
    this.retrier = retrier;
  }

  LazyBlockList fetch(BlockListType blockListType) {
    // TODO: use more informative exceptions to describe retriable errors
    return retrier.callWithRetry(
        () -> tryFetch(blockListType),
        e -> e instanceof BsaException && ((BsaException) e).isRetriable());
  }

  LazyBlockList tryFetch(BlockListType blockListType) {
    try {
      URL dataUrl = new URL(blockListUrls.get(blockListType.name()));
      logger.atInfo().log("Downloading from  %s", dataUrl);
      HttpsURLConnection connection =
          (HttpsURLConnection) urlConnectionService.createConnection(dataUrl);
      connection.setRequestMethod(HttpMethods.GET);
      connection.setRequestProperty("Authorization", "Bearer " + credential.getAuthToken());
      int code = connection.getResponseCode();
      if (code != SC_OK) {
        String errorDetails = "";
        try (InputStream errorStream = connection.getErrorStream()) {
          errorDetails = new String(ByteStreams.toByteArray(errorStream), UTF_8);
        } catch (NullPointerException e) {
          // No error message.
        } catch (Exception e) {
          errorDetails = "Failed to retrieve error message: " + e.getMessage();
        }
        throw new BsaException(
            String.format(
                "Status code: [%s], error: [%s], details: [%s]",
                code, connection.getResponseMessage(), errorDetails),
            /* retriable= */ true);
      }
      return new LazyBlockList(blockListType, connection);
    } catch (IOException e) {
      throw new BsaException(e, /* retriable= */ true);
    } catch (GeneralSecurityException e) {
      throw new BsaException(e, /* retriable= */ false);
    }
  }

  static class LazyBlockList implements Closeable {

    private final BlockListType blockListType;

    private final HttpsURLConnection connection;

    private final BufferedInputStream inputStream;
    private final String checksum;

    LazyBlockList(BlockListType blockListType, HttpsURLConnection connection) throws IOException {
      this.blockListType = blockListType;
      this.connection = connection;
      this.inputStream = new BufferedInputStream(connection.getInputStream());
      this.checksum = readChecksum();
    }

    /** Reads the BSA-generated checksum, which is the first line of the input. */
    private String readChecksum() throws IOException {
      StringBuilder checksum = new StringBuilder();
      char ch;
      while ((ch = peekInputStream()) != Character.MAX_VALUE && !Character.isWhitespace(ch)) {
        checksum.append((char) inputStream.read());
      }
      while ((ch = peekInputStream()) != Character.MAX_VALUE && Character.isWhitespace(ch)) {
        inputStream.read();
      }
      return checksum.toString();
    }

    char peekInputStream() throws IOException {
      inputStream.mark(1);
      int byteValue = inputStream.read();
      inputStream.reset();
      return (char) byteValue;
    }

    BlockListType getName() {
      return blockListType;
    }

    String checksum() {
      return checksum;
    }

    void consumeAll(BiConsumer<byte[], Integer> consumer) throws IOException {
      byte[] buffer = new byte[1024];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        consumer.accept(buffer, bytesRead);
      }
    }

    @Override
    public void close() {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          // Fall through to close the connection.
        }
      }
      connection.disconnect();
    }
  }
}
