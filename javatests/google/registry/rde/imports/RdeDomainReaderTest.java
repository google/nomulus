// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveContact;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.rde.imports.RdeDomainReader;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeDomainReader} */
@RunWith(JUnit4.class)
public class RdeDomainReaderTest {

  private static final ByteSource DEPOSIT_1_DOMAIN = RdeImportsTestData.get("deposit_1_domain.xml");
  private static final ByteSource DEPOSIT_3_DOMAIN = RdeImportsTestData.get("deposit_3_domain.xml");
  private static final ByteSource DEPOSIT_4_DOMAIN = RdeImportsTestData.get("deposit_4_domain.xml");
  private static final ByteSource DEPOSIT_10_DOMAIN = RdeImportsTestData.get("deposit_10_domain.xml");
  private static final String IMPORT_BUCKET_NAME = "rde-import";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Before
  public void before() {
    createTld("test");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
  }

  /** Reads at least one result at 0 offset 1 maxResults */
  @Test
  public void testZeroOffsetOneResult_readsOne() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    RdeDomainReader reader = getReader(0, 1);
    checkDomain(reader.next(), "example1.test", "Dexample1-TEST");
  }

  /** Reads at most one at 0 offset 1 maxResults */
  @Test
  public void testZeroOffsetOneResult_stopsAfterOne() throws Exception {
    pushToGcs(DEPOSIT_3_DOMAIN);
    RdeDomainReader reader = getReader(0, 1);
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Skips already-processed records after rehydration */
  @Test
  public void testZeroOffsetOneResult_skipsOneAfterRehydration() throws Exception {
    pushToGcs(DEPOSIT_3_DOMAIN);
    RdeDomainReader reader = getReader(0, 1);
    reader.next();
    reader.endSlice();

    reader = cloneObject(reader);
    reader.beginSlice();
    // reader will not advance any further
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Reads three domains */
  @Test
  public void testZeroOffsetThreeResult_readsThree() throws Exception {
    pushToGcs(DEPOSIT_3_DOMAIN);
    RdeDomainReader reader = getReader(0, 3);
    checkDomain(reader.next(), "example1.test", "Dexample1-TEST");
    checkDomain(reader.next(), "example2.test", "Dexample2-TEST");
    checkDomain(reader.next(), "example3.test", "Dexample3-TEST");
  }

  /** Stops reading at 3 maxResults */
  @Test
  public void testZeroOffsetThreeResult_stopsAtThree() throws Exception {
    pushToGcs(DEPOSIT_4_DOMAIN);
    RdeDomainReader reader = getReader(0, 3);
    for (int i = 0; i < 3; i++) {
      reader.next();
    }
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Reads one domain from file then stops at end of file */
  @Test
  public void testZeroOffsetThreeResult_endOfFile() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    RdeDomainReader reader = getReader(0, 3);
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Skips three domains with offset of three */
  @Test
  public void testThreeOffsetOneResult_skipsThree() throws Exception {
    pushToGcs(DEPOSIT_4_DOMAIN);
    RdeDomainReader reader = getReader(3, 1);
    checkDomain(reader.next(), "example4.test", "Dexample4-TEST");
  }

  /** Skips four domains after advancing once at three offset, then rehydrating */
  @Test
  public void testThreeOffsetTwoResult_skipsFourAfterRehydration() throws Exception {
    pushToGcs(DEPOSIT_10_DOMAIN);
    RdeDomainReader reader = getReader(3, 2);
    reader.next();
    reader.endSlice();
    reader = cloneObject(reader);
    reader.beginSlice();
    checkDomain(reader.next(), "example5.test", "Dexample5-TEST");
  }

  /** Reads three at zero offset three results with rehydration in the middle */
  @Test
  public void testZeroOffsetThreeResult_readsThreeWithRehydration() throws Exception {
    pushToGcs(DEPOSIT_4_DOMAIN);
    RdeDomainReader reader = getReader(0, 3);
    checkDomain(reader.next(), "example1.test", "Dexample1-TEST");
    reader.endSlice();
    reader = cloneObject(reader);
    reader.beginSlice();
    checkDomain(reader.next(), "example2.test", "Dexample2-TEST");
    checkDomain(reader.next(), "example3.test", "Dexample3-TEST");
  }

  /** Stops reading at three with zero offset three results with rehydration in the middle */
  @Test
  public void testZeroOffsetThreeResult_stopsAtThreeWithRehydration() throws Exception {
    pushToGcs(DEPOSIT_4_DOMAIN);
    RdeDomainReader reader = getReader(0, 3);
    reader.next();
    reader.endSlice();
    reader = cloneObject(reader);
    reader.beginSlice();
    reader.next();
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
          .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  /** Creates a deep copy of the {@link T} */
  public <T> T cloneObject(
      T object) throws Exception {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    ObjectOutputStream oout = new ObjectOutputStream(bout);
    oout.writeObject(object);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    ObjectInputStream oin = new ObjectInputStream(bin);
    @SuppressWarnings("unchecked")
    T result = (T) oin.readObject();
    return result;
  }

  /** Verifies that domain name and ROID match expected values */
  private void checkDomain(JaxbFragment<XjcRdeDomainElement> fragment, String domainName, String repoId) throws Exception {
    assertThat(fragment).isNotNull();
    XjcRdeDomain domain = fragment.getInstance().getValue();
    assertThat(domain.getName()).isEqualTo(domainName);
    assertThat(domain.getRoid()).isEqualTo(repoId);
  }

  /** Gets a new {@link RdeDomainReader} with specified offset and maxResults */
  private RdeDomainReader getReader(int offset, int maxResults) throws Exception {
    RdeDomainReader reader =
        new RdeDomainReader(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME, offset, maxResults);
    reader.beginSlice();
    return reader;
  }
}
