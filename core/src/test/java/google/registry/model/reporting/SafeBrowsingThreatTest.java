package google.registry.model.reporting;

import google.registry.persistence.transaction.JpaTestRules;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.extension.RegisterExtension;

public class SafeBrowsingThreatTest {
  private SafeBrowsingThreat safeBrowsingThreat;

  private final FakeClock fakeClock = new FakeClock();

  /** Create a new persisted SafeBrowsingThreat entity. */
  @RegisterExtension
  JpaTestRules.JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  public void setUp() {
    safeBrowsingThreat = {
            new SafeBrowsingThreat.Builder()
                  .setId()
    }
  }
}
