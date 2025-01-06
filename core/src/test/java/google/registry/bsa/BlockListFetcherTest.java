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

import static com.google.common.truth.Truth.assertThat;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.api.BsaCredential;
import google.registry.bsa.api.BsaException;
import google.registry.request.UrlConnectionService;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BlockListFetcher}. */
@ExtendWith(MockitoExtension.class)
class BlockListFetcherTest {
  @Mock HttpsURLConnection connection;
  @Mock UrlConnectionService connectionService;
  @Mock BsaCredential credential;

  BlockListFetcher fetcher;

  @BeforeEach
  void setup() {
    fetcher =
        new BlockListFetcher(
            connectionService,
            credential,
            ImmutableMap.of("BLOCK", "https://block", "BLOCK_PLUS", "https://blockplus"),
            new Retrier(new SystemSleeper(), 2));
  }

  void setupMocks() throws Exception {
    when(connectionService.createConnection(any(URL.class))).thenReturn(connection);
    when(credential.getAuthToken()).thenReturn("authToken");
  }

  @Test
  void tryFetch_bsaChecksumFetched() throws Exception {
    setupMocks();
    when(connection.getResponseCode()).thenReturn(SC_OK);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("bsa-checksum\ndata".getBytes(UTF_8)));
    LazyBlockList download = fetcher.tryFetch(BlockListType.BLOCK);
    assertThat(download.getName()).isEqualTo(BlockListType.BLOCK);
    assertThat(download.checksum()).isEqualTo("bsa-checksum");
    verify(connection, times(1)).setRequestMethod("GET");
    verify(connection, times(1)).setRequestProperty("Authorization", "Bearer authToken");
  }

  @Test
  void tryFetch_ifStatusNotOK_throwRetriable() throws Exception {
    setupMocks();
    when(connection.getResponseCode()).thenReturn(201);
    assertThat(
            assertThrows(BsaException.class, () -> fetcher.tryFetch(BlockListType.BLOCK))
                .isRetriable())
        .isTrue();
  }

  @Test
  void tryFetch_IOException_retriable() throws Exception {
    setupMocks();
    when(connection.getResponseCode()).thenThrow(new IOException());
    assertThat(
            assertThrows(BsaException.class, () -> fetcher.tryFetch(BlockListType.BLOCK))
                .isRetriable())
        .isTrue();
  }

  @Test
  void tryFetch_SecurityException_notRetriable() throws Exception {
    when(connectionService.createConnection(any(URL.class)))
        .thenThrow(new GeneralSecurityException());
    assertThat(
            assertThrows(BsaException.class, () -> fetcher.tryFetch(BlockListType.BLOCK))
                .isRetriable())
        .isFalse();
  }

  @Test
  void lazyBlock_blockListFetched() throws Exception {
    setupMocks();
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("bsa-checksum\ndata".getBytes(UTF_8)));
    when(connection.getResponseCode()).thenReturn(SC_OK);
    try (LazyBlockList download = fetcher.tryFetch(BlockListType.BLOCK)) {
      StringBuilder sb = new StringBuilder();
      download.consumeAll(
          (buffer, length) -> {
            String snippet = new String(buffer, 0, length, UTF_8);
            sb.append(snippet);
          });
      assertThat(sb.toString()).isEqualTo("data");
    }
    verify(connection, times(1)).disconnect();
  }

  @Test
  void lazyBlockPlus_success() throws Exception {
    setupMocks();
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("checksum\ndata\n".getBytes(UTF_8)));
    when(connection.getResponseCode()).thenReturn(SC_OK);
    try (LazyBlockList lazyBlockList = fetcher.tryFetch(BlockListType.BLOCK_PLUS)) {
      assertThat(readBlockData(lazyBlockList)).isEqualTo("data\n");
      assertThat(lazyBlockList.checksum()).isEqualTo("checksum");
    }
    verify(connection, times(1)).disconnect();
  }

  @Test
  void lazyBlockPlus_checksum_cr() throws Exception {
    setupMocks();
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("checksum\rdata\n".getBytes(UTF_8)));
    when(connection.getResponseCode()).thenReturn(SC_OK);
    try (LazyBlockList lazyBlockList = fetcher.tryFetch(BlockListType.BLOCK_PLUS)) {
      assertThat(readBlockData(lazyBlockList)).isEqualTo("data\n");
      assertThat(lazyBlockList.checksum()).isEqualTo("checksum");
    }
    verify(connection, times(1)).disconnect();
  }

  @Test
  void lazyBlockPlus_checksum_crnl() throws Exception {
    setupMocks();
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("checksum\r\ndata\n".getBytes(UTF_8)));
    when(connection.getResponseCode()).thenReturn(SC_OK);
    try (LazyBlockList lazyBlockList = fetcher.tryFetch(BlockListType.BLOCK_PLUS)) {
      assertThat(readBlockData(lazyBlockList)).isEqualTo("data\n");
      assertThat(lazyBlockList.checksum()).isEqualTo("checksum");
    }
    verify(connection, times(1)).disconnect();
  }

  private String readBlockData(LazyBlockList lazyBlockList) throws Exception {
    StringBuilder sb = new StringBuilder();
    lazyBlockList.consumeAll(
        (buffer, length) -> {
          String snippet = new String(buffer, 0, length, UTF_8);
          sb.append(snippet);
        });
    return sb.toString();
  }
}
