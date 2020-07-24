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

package google.registry.mapreduce.inputs;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.mapreduce.inputs.EppResourceInputs.createKeyInput;
import static google.registry.model.index.EppResourceIndexBucket.getBucketKey;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistEppResourceInFirstBucket;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static org.junit.Assert.assertThrows;

import com.google.appengine.tools.mapreduce.InputReader;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.testing.AppEngineExtension;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests {@link EppResourceInputs} */
class EppResourceInputsTest {

  private static final double EPSILON = 0.0001;

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @SuppressWarnings("unchecked")
  private <T> T serializeAndDeserialize(T obj) throws Exception {
    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream objectOut = new ObjectOutputStream(byteOut)) {
      objectOut.writeObject(obj);
      try (ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
          ObjectInputStream objectIn = new ObjectInputStream(byteIn)) {
        return (T) objectIn.readObject();
      }
    }
  }

  @Test
  void testSuccess_keyInputType_polymorphicBaseType() {
    createKeyInput(EppResource.class);
  }

  @Test
  void testFailure_keyInputType_noInheritanceBetweenTypes_eppResource() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> createKeyInput(EppResource.class, DomainBase.class));
    assertThat(thrown).hasMessageThat().contains("inheritance");
  }

  @Test
  void testFailure_entityInputType_noInheritanceBetweenTypes_eppResource() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> createEntityInput(EppResource.class, DomainBase.class));
    assertThat(thrown).hasMessageThat().contains("inheritance");
  }

  @Test
  void testFailure_entityInputType_noInheritanceBetweenTypes_subclasses() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> createEntityInput(EppResource.class, ContactResource.class));
    assertThat(thrown).hasMessageThat().contains("inheritance");
  }

  @Test
  void testReaderCountMatchesBucketCount() throws Exception {
    assertThat(createKeyInput(DomainBase.class).createReaders()).hasSize(3);
    assertThat(createEntityInput(DomainBase.class).createReaders()).hasSize(3);
  }

  @Test
  void testKeyInput_oneReaderPerBucket() throws Exception {
    createTld("tld");
    Set<Key<DomainBase>> domains = new HashSet<>();
    for (int i = 1; i <= 3; i++) {
      Key<DomainBase> key = Key.create(newDomainBase(i + ".tld"));
      domains.add(key);
      persistResource(EppResourceIndex.create(getBucketKey(i), key));
    }
    Set<Key<DomainBase>> seen = new HashSet<>();
    for (InputReader<Key<DomainBase>> reader : createKeyInput(DomainBase.class).createReaders()) {
      reader.beginShard();
      reader.beginSlice();
      seen.add(reader.next());
      try {
        Key<DomainBase> key = reader.next();
        assertWithMessage("Unexpected element: %s", key).fail();
      } catch (NoSuchElementException expected) {
      }
    }
    assertThat(seen).containsExactlyElementsIn(domains);
  }

  @Test
  void testEntityInput_oneReaderPerBucket() throws Exception {
    createTld("tld");
    Set<DomainBase> domains = new HashSet<>();
    for (int i = 1; i <= 3; i++) {
      // Persist the domain as a simple resource so that it doesn't automatically get an ERI.
      DomainBase domain = persistSimpleResource(newDomainBase(i + ".tld"));
      domains.add(domain);
      persistResource(EppResourceIndex.create(getBucketKey(i), Key.create(domain)));
    }
    Set<DomainBase> seen = new HashSet<>();
    for (InputReader<DomainBase> reader : createEntityInput(DomainBase.class).createReaders()) {
      reader.beginShard();
      reader.beginSlice();
      seen.add(reader.next());
      try {
        DomainBase domain = reader.next();
        assertWithMessage("Unexpected element: %s", domain).fail();
      } catch (NoSuchElementException expected) {
      }
    }
    assertThat(seen).containsExactlyElementsIn(domains);
  }

  @Test
  void testSuccess_keyReader_survivesAcrossSerialization() throws Exception {
    createTld("tld");
    DomainBase domainA = persistEppResourceInFirstBucket(newDomainBase("a.tld"));
    DomainBase domainB = persistEppResourceInFirstBucket(newDomainBase("b.tld"));
    // Should be ignored. We'll know if it isn't because the progress counts will be off.
    persistActiveContact("contact");
    Set<Key<DomainBase>> seen = new HashSet<>();
    InputReader<Key<DomainBase>> reader = createKeyInput(DomainBase.class).createReaders().get(0);
    reader.beginShard();
    reader.beginSlice();
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.5);
    reader.endSlice();
    reader = serializeAndDeserialize(reader);
    reader.beginSlice();
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.5);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(1);
    assertThat(seen).containsExactly(Key.create(domainA), Key.create(domainB));
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void testSuccess_entityReader_survivesAcrossSerialization() throws Exception {
    createTld("tld");
    DomainBase domainA = persistEppResourceInFirstBucket(newDomainBase("a.tld"));
    DomainBase domainB = persistEppResourceInFirstBucket(newDomainBase("b.tld"));
    // Should be ignored. We'll know if it isn't because the progress counts will be off.
    persistActiveContact("contact");
    Set<DomainBase> seen = new HashSet<>();
    InputReader<DomainBase> reader = createEntityInput(DomainBase.class).createReaders().get(0);
    reader.beginShard();
    reader.beginSlice();
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.5);
    reader.endSlice();
    InputReader<DomainBase> deserializedReader = serializeAndDeserialize(reader);
    deserializedReader.beginSlice();
    assertThat(deserializedReader.getProgress()).isWithin(EPSILON).of(0.5);
    seen.add(deserializedReader.next());
    assertThat(deserializedReader.getProgress()).isWithin(EPSILON).of(1);
    deserializedReader.endSlice();
    deserializedReader.endShard();
    assertThat(seen).containsExactly(domainA, domainB);
    assertThrows(NoSuchElementException.class, deserializedReader::next);
  }

  @Test
  void testSuccess_entityReader_filtersOnMultipleTypes() throws Exception {
    createTld("tld");
    DomainBase domain = persistEppResourceInFirstBucket(newDomainBase("a.tld"));
    HostResource host = persistEppResourceInFirstBucket(newHostResource("ns1.example.com"));
    persistEppResourceInFirstBucket(newContactResource("contact"));
    Set<EppResource> seen = new HashSet<>();
    InputReader<EppResource> reader =
        EppResourceInputs.<EppResource>createEntityInput(DomainBase.class, HostResource.class)
            .createReaders()
            .get(0);
    reader.beginShard();
    reader.beginSlice();
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.5);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(1.0);
    assertThat(seen).containsExactly(domain, host);
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void testSuccess_entityReader_noFilteringWhenUsingEppResource() throws Exception {
    createTld("tld");
    ContactResource contact = persistEppResourceInFirstBucket(newContactResource("contact"));
    // Specify the contact since persistActiveDomain{Application} creates a hidden one.
    DomainBase domain1 = persistEppResourceInFirstBucket(newDomainBase("a.tld", contact));
    DomainBase domain2 = persistEppResourceInFirstBucket(newDomainBase("b.tld", contact));
    HostResource host = persistEppResourceInFirstBucket(newHostResource("ns1.example.com"));
    Set<EppResource> seen = new HashSet<>();
    InputReader<EppResource> reader = createEntityInput(EppResource.class).createReaders().get(0);
    reader.beginShard();
    reader.beginSlice();
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.25);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.5);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(0.75);
    seen.add(reader.next());
    assertThat(reader.getProgress()).isWithin(EPSILON).of(1.0);
    assertThat(seen).containsExactly(domain1, domain2, host, contact);
    assertThrows(NoSuchElementException.class, reader::next);
  }
}
