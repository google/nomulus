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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import google.registry.bsa.api.BsaCredential;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.ReservedList;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.UrlConnectionService;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link UploadBsaUnavailableDomainsAction}. */
@ExtendWith(MockitoExtension.class)
public class UploadBsaUnavailableDomainsActionTest {

  private static final String BUCKET = "domain-registry-bsa";

  private static final String API_URL = "https://upload.test/bsa";

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-02-02T02:02:02Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private UploadBsaUnavailableDomainsAction action;

  @Mock UrlConnectionService connectionService;

  @Mock BsaCredential bsaCredential;

  @Mock BsaEmailSender emailSender;

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final FakeResponse response = new FakeResponse();

  @BeforeEach
  void beforeEach() {
    ReservedList reservedList =
        persistReservedList(
            "tld-reserved_list",
            "tine,FULLY_BLOCKED",
            "flagrant,NAME_COLLISION",
            "jimmy,RESERVED_FOR_SPECIFIC_USE");
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(reservedList)
            .setBsaEnrollStartTime(Optional.of(START_OF_TIME))
            .setTldType(TldType.REAL)
            .build());
    action =
        new UploadBsaUnavailableDomainsAction(
            clock, bsaCredential, gcsUtils, emailSender, BUCKET, API_URL, response);
  }

  @Test
  void calculatesEntriesCorrectly() throws Exception {
    persistActiveDomain("foobar.tld");
    persistActiveDomain("ace.tld");
    persistDeletedDomain("not-blocked.tld", clock.nowUtc().minusDays(1));
    action.run();
    BlobId existingFile =
        BlobId.of(BUCKET, String.format("unavailable_domains_%s.txt", clock.nowUtc()));
    String blockList = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    assertThat(blockList).isEqualTo("ace.tld\nflagrant.tld\nfoobar.tld\njimmy.tld\ntine.tld");
    assertThat(blockList).doesNotContain("not-blocked.tld");

    // This test currently fails in the upload-to-bsa step.
    verify(emailSender, times(1))
        .sendNotification("BSA daily upload completed with errors", "Please see logs for details.");

    // TODO(mcilwain): Add test of BSA API upload as well.
  }
}
