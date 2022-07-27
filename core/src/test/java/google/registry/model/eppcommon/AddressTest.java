// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.eppcommon;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.AddressTest.TestEntity.TestAddress;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.io.StringReader;
import java.io.StringWriter;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link Address}. */
class AddressTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private static final String ENTITY_XML =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
          + "<testEntity>\n"
          + "    <address>\n"
          + "        <street>123 W 14th St</street>\n"
          + "        <street>8th Fl</street>\n"
          + "        <street>Rm 8</street>\n"
          + "        <city>New York</city>\n"
          + "        <sp>NY</sp>\n"
          + "        <pc>10011</pc>\n"
          + "        <cc>US</cc>\n"
          + "    </address>\n"
          + "</testEntity>\n";

  private TestAddress address = createAddress("123 W 14th St", "8th Fl", "Rm 8");
  private TestEntity entity = new TestEntity(1L, address);

  private static TestEntity saveAndLoad(TestEntity entity) {
    insertInDb(entity);
    return loadByEntity(entity);
  }

  @Test
  void testSuccess_setStreet() {
    assertAddress(address, "123 W 14th St", "8th Fl", "Rm 8");
  }
  /** Test the persist behavior. */
  @Test
  void testSuccess_saveAndLoadStreetLines() {
    assertAddress(saveAndLoad(entity).address, "123 W 14th St", "8th Fl", "Rm 8");
  }

  /** Test the merge behavior. */
  @Test
  void testSuccess_putAndLoadStreetLines() {
    jpaTm().transact(() -> jpaTm().put(entity));
    assertAddress(loadByEntity(entity).address, "123 W 14th St", "8th Fl", "Rm 8");
  }

  @Test
  void testSuccess_setsNullStreetLine() {
    entity = new TestEntity(1L, createAddress("line1", "line2"));
    TestEntity savedEntity = saveAndLoad(entity);
    assertAddress(savedEntity.address, "line1", "line2");
    assertThat(savedEntity.address.streetLine3).isNull();
  }

  @Test
  void testFailure_tooManyStreetLines() {
    assertThrows(
        IllegalArgumentException.class, () -> createAddress("line1", "line2", "line3", "line4"));
  }

  @Test
  void testFailure_emptyStreetLine() {
    assertThrows(IllegalArgumentException.class, () -> createAddress("line1", "", "line3"));
  }

  @Test
  void testSuccess_pojoToAndFromXml() throws Exception {
    JAXBContext jaxbContext = JAXBContext.newInstance(TestEntity.class);
    // POJO to XML
    Marshaller marshaller = jaxbContext.createMarshaller();
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
    StringWriter sw = new StringWriter();
    marshaller.marshal(entity, sw);
    String xml = sw.toString();
    assertThat(xml).isEqualTo(ENTITY_XML);
    // XML to POJO
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    TestEntity unmarshalledEntity = (TestEntity) unmarshaller.unmarshal(new StringReader(xml));
    assertAddress(unmarshalledEntity.address, "123 W 14th St", "8th Fl", "Rm 8");
  }

  private static TestAddress createAddress(String... streetList) {
    return new TestAddress.Builder()
        .setStreet(ImmutableList.copyOf(streetList))
        .setCity("New York")
        .setState("NY")
        .setZip("10011")
        .setCountryCode("US")
        .build();
  }

  private static void assertAddress(TestAddress address, String... streetList) {
    assertThat(address.street).containsExactly((Object[]) streetList);
    if (streetList.length > 0) {
      assertThat(address.streetLine1).isEqualTo(streetList[0]);
    }
    if (streetList.length > 1) {
      assertThat(address.streetLine2).isEqualTo(streetList[1]);
    }
    if (streetList.length > 2) {
      assertThat(address.streetLine3).isEqualTo(streetList[2]);
    }
  }

  @Entity(name = "TestEntity")
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  static class TestEntity extends ImmutableObject {

    @XmlTransient @Id long id;

    @XmlElement TestAddress address;

    TestEntity() {}

    TestEntity(Long id, TestAddress address) {
      this.id = id;
      this.address = address;
    }

    @Embeddable
    public static class TestAddress extends Address {

      public static class Builder extends Address.Builder<TestAddress> {}
    }
  }
}
