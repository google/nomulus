package google.registry.bsa.persistence;

import google.registry.model.CreateAutoTimestamp;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;

/** A domain name that cannot be blocked because it is already registered or */
@Entity
public class UnblockedDomain {
  @Id String label;
  @Id String tld;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Reason reason;

  CreateAutoTimestamp createTime = CreateAutoTimestamp.create(null);

  UnblockedDomain() {}

  enum Reason {
    REGISTERED,
    RESERVED;
  }
}
