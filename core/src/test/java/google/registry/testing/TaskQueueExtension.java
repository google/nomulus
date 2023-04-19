// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.io.Files.asCharSink;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig;
import com.google.apphosting.api.ApiProxy;
import google.registry.model.annotations.DeleteAfterMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit extension that sets up App Engine task queue environment. */
@DeleteAfterMigration
public final class TaskQueueExtension implements BeforeEachCallback, AfterEachCallback {

  /**
   * The GAE testing library requires queue.xml to be a file, not a resource in a jar, so we read it
   * in here and write it to a temporary file later.
   */
  private static final String QUEUE_XML =
      readResourceUtf8("google/registry/env/common/default/WEB-INF/to_be_deleted_queue.xml");

  private LocalServiceTestHelper helper;
  private Path queueFile;

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    queueFile = Files.createTempFile("queue", ".xml");
    asCharSink(queueFile.toFile(), UTF_8).write(QUEUE_XML);
    helper =
        new LocalServiceTestHelper(
            new LocalTaskQueueTestConfig().setQueueXmlPath(queueFile.toAbsolutePath().toString()));
    helper.setUp();
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    helper.tearDown();
    Files.delete(queueFile);
    ApiProxy.setEnvironmentForCurrentThread(null);
  }
}
