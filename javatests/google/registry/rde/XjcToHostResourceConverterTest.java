package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;

import com.google.appengine.repackaged.com.google.common.net.InetAddresses;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineRule;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import java.io.InputStream;
import java.util.Arrays;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XjcToHostResourceConverter}
 */
@RunWith(JUnit4.class)
public class XjcToHostResourceConverterTest {

  private static final ByteSource HOST_XML = RdeTestData.get("host_fragment.xml");

  //List of packages to initialize JAXBContext
  private static final String JAXB_CONTEXT_PACKAGES = Joiner.on(":")
      .join(Arrays.asList(
          "google.registry.xjc.contact",
          "google.registry.xjc.domain",
          "google.registry.xjc.host",
          "google.registry.xjc.mark",
          "google.registry.xjc.rde",
          "google.registry.xjc.rdecontact",
          "google.registry.xjc.rdedomain",
          "google.registry.xjc.rdeeppparams",
          "google.registry.xjc.rdeheader",
          "google.registry.xjc.rdeidn",
          "google.registry.xjc.rdenndn",
          "google.registry.xjc.rderegistrar",
          "google.registry.xjc.smd"));

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private Unmarshaller unmarshaller;

  @Before
  public void before() throws Exception {
    createTld("example");
    unmarshaller = JAXBContext.newInstance(JAXB_CONTEXT_PACKAGES)
        .createUnmarshaller();
  }

  @Test
  public void testConvertHostResource() throws Exception {
    XjcRdeHost xjcHost = getHost();
    HostResource host = XjcToHostResourceConverter.convert(xjcHost);
    assertThat(host.getFullyQualifiedHostName()).isEqualTo("ns1.example1.test");
    assertThat(host.getRepoId()).isEqualTo("Hns1_example1_test-TEST");
    assertThat(host.getStatusValues())
        .isEqualTo(ImmutableSet.of(StatusValue.OK, StatusValue.LINKED));
    assertThat(host.getInetAddresses()).isEqualTo(
        ImmutableSet.of(
            InetAddresses.forString("192.0.2.2"),
            InetAddresses.forString("192.0.2.29"),
            InetAddresses.forString("1080:0:0:0:8:800:200C:417A")));
    assertThat(host.getCurrentSponsorClientId()).isEqualTo("RegistrarX");
    assertThat(host.getCreationClientId()).isEqualTo("RegistrarX");
    assertThat(host.getCreationTime()).isEqualTo(DateTime.parse("1999-05-08T12:10:00.0Z"));
    assertThat(host.getLastEppUpdateClientId()).isEqualTo("RegistrarX");
    assertThat(host.getLastEppUpdateTime()).isEqualTo(DateTime.parse("2009-10-03T09:34:00.0Z"));
    assertThat(host.getLastTransferTime()).isEqualTo(DateTime.parse("2008-10-03T09:34:00.0Z"));
  }

  // included to pass the round-robin sharding filter
  @Test
  public void testNothing1() {}

  // included to pass the round-robin sharding filter
  @Test
  public void testNothing2() {}

  // included to pass the round-robin sharding filter
  @Test
  public void testNothing3() {}

  private XjcRdeHost getHost() throws Exception {
    try (InputStream ins = HOST_XML.openStream()) {
      return ((XjcRdeHostElement)unmarshaller.unmarshal(ins)).getValue();
    }
  }
}
