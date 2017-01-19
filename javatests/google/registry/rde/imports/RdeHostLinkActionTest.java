package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.request.Response;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class RdeHostLinkActionTest extends MapreduceTestCase<RdeHostLinkAction> {
  private static final ByteSource DEPOSIT_1_HOST = RdeImportsTestData.get("deposit_1_host.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  private MapreduceRunner mrRunner;

  private Response response;

  private final Optional<Integer> mapShards = Optional.absent();

  @Before
  public void before() throws Exception {
    createTld("test");
    response = new FakeResponse();
    mrRunner = new MapreduceRunner(Optional.<Integer>absent(), Optional.<Integer>absent());
    action = new RdeHostLinkAction(
        mrRunner,
        response,
        IMPORT_BUCKET_NAME,
        IMPORT_FILE_NAME,
        mapShards);
  }

  @Test
  public void test_mapreduceSuccessfullyLinksHost() throws Exception {
    // Create host and domain first
    persistResource(
        newHostResource("ns1.example1.test")
        .asBuilder()
        .setRepoId("Hns1_example1_test-TEST")
        .build());
    DomainResource superordinateDomain =
        persistActiveDomain("example1.test");
    ForeignKeyDomainIndex.create(superordinateDomain, END_OF_TIME);
    Key<DomainResource> superOrdinateDomainKey = Key.create(superordinateDomain);
    pushToGcs(DEPOSIT_1_HOST);
    runMapreduce();
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    assertThat(hosts.get(0).getSuperordinateDomain()).isEqualTo(superOrdinateDomainKey);
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
          .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  private static byte[] loadHostXml(ByteSource source) throws IOException {
    byte[] result = new byte[(int)source.size()];
    try (InputStream inStream = source.openStream()) {
      ByteStreams.readFully(inStream, result);
    }
    return result;
  }
}
