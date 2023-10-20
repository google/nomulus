package google.registry.bsa.persistence;

import google.registry.model.CreateAutoTimestamp;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;

/** Label to be blocked. The label text is valid in at least one of the TLDs enrolled with BSA. */
@Entity
public class BsaLabel {

  @Id String label;

  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  @Nullable DateTime bsaRemovalTime;

  BsaLabel() {}

  public String getLabel() {
    return label;
  }

  public BsaLabel setBsaRemovalTime(DateTime time) {
    bsaRemovalTime = time;
    return this;
  }

  public static VKey<BsaLabel> vKey(String label) {
    return VKey.create(BsaLabel.class, label);
  }
}
