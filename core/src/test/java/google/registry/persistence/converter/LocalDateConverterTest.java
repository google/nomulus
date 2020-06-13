package google.registry.persistence.converter;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import google.registry.schema.replay.EntityTest;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PersistenceException;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** Unit tests for {@link LocalDateConverter}. */
@RunWith(JUnit4.class)
public class LocalDateConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder()
          .withEntityClass(LocalDateConverterTestEntity.class)
          .buildUnitTestRule();

  private final LocalDateConverter converter = new LocalDateConverter();

  private final LocalDate date = LocalDate.parse("2020-06-10", ISODateTimeFormat.date());

  @Test
  public void convertToDatabaseColumn_returnsNullIfInputIsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  public void convertToDatabaseColumn_convertsCorrectly() {
    assertThat(converter.convertToDatabaseColumn(date)).isEqualTo("2020-06-10");
  }

  @Test
  public void convertToEntityAttribute_returnsNullIfInputIsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  public void convertToEntityAttribute_convertsCorrectly() {
    assertThat(converter.convertToEntityAttribute("2020-06-10")).isEqualTo(date);
  }

  @Test
  public void testSaveAndLoad_success() {
    instantiateAndPersistTestEntity();
    LocalDateConverterTestEntity retrievedEntity =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(LocalDateConverterTestEntity.class, "id"));
    assertThat(retrievedEntity.date.toString()).isEqualTo("2020-06-10");
  }

  @Test
  public void invalidLocalDate() {
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"LocalDateConverterTestEntity\" (name, date) VALUES('id', 'XXXX')")
                    .executeUpdate());
    assertThrows(
        PersistenceException.class,
        () ->
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .find(LocalDateConverterTestEntity.class, "id")
                            .date));
  }

  @Test
  public void roundTripConversion() {
    instantiateAndPersistTestEntity();
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createNativeQuery(
                                "SELECT date FROM \"LocalDateConverterTestEntity\" WHERE name = 'id'")
                            .getResultList()))
        .containsExactly("2020-06-10");
    LocalDateConverterTestEntity persisted =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(LocalDateConverterTestEntity.class, "id"));
    assertThat(persisted.date).isEqualTo(date);
  }

  private void instantiateAndPersistTestEntity() {
    LocalDateConverterTestEntity entity = new LocalDateConverterTestEntity(date);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
  }

  /** Override entity name to avoid the nested class reference. */
  @Entity(name = "LocalDateConverterTestEntity")
  @EntityTest.EntityForTesting
  private static class LocalDateConverterTestEntity extends ImmutableObject {

    @Id String name = "id";

    LocalDate date;

    public LocalDateConverterTestEntity() {}

    LocalDateConverterTestEntity(LocalDate date) {
      this.date = date;
    }
  }
}
