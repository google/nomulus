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

package google.registry.persistence.converter;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractTypeDescriptor;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.spi.JdbcRecommendedSqlTypeMappingContext;
import org.hibernate.type.descriptor.sql.BasicBinder;
import org.hibernate.type.descriptor.sql.BasicExtractor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;
import org.postgresql.util.PGInterval;

/**
 * The {@link JavaTypeDescriptor} and {@link SqlTypeDescriptor} for {@link PGInterval}.
 *
 * @see <a
 *     href="https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-jpa-convert">JPA
 *     2.1 AttributeConverters</a>
 */
public class IntervalDescriptor extends AbstractTypeDescriptor<PGInterval>
    implements SqlTypeDescriptor {
  public static final int COLUMN_TYPE = Types.JAVA_OBJECT;
  public static final String COLUMN_NAME = "interval";
  private static final IntervalDescriptor INSTANCE = new IntervalDescriptor();

  private IntervalDescriptor() {
    super(PGInterval.class);
  }

  public static IntervalDescriptor getInstance() {
    return INSTANCE;
  }

  @Override
  public PGInterval fromString(String string) {
    throw new UnsupportedOperationException(
        "Constructing IntervalDescriptor from string is not allowed");
  }

  @Override
  public <X> X unwrap(PGInterval value, Class<X> type, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (PGInterval.class.isAssignableFrom(type)) {
      return (X) value;
    }
    throw unknownUnwrap(type);
  }

  @Override
  public <X> PGInterval wrap(X value, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (value instanceof PGInterval) {
      try {
        return new PGInterval(value.toString());
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
    throw unknownWrap(value.getClass());
  }

  @Override
  public int getSqlType() {
    return COLUMN_TYPE;
  }

  @Override
  public SqlTypeDescriptor getJdbcRecommendedSqlType(JdbcRecommendedSqlTypeMappingContext context) {
    return this;
  }

  @Override
  public boolean canBeRemapped() {
    return false;
  }

  @Override
  public <X> ValueBinder<X> getBinder(JavaTypeDescriptor<X> javaTypeDescriptor) {
    return new BasicBinder<X>(javaTypeDescriptor, this) {
      @Override
      protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
          throws SQLException {
        st.setObject(index, new PGInterval(value.toString()));
      }

      @Override
      protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
          throws SQLException {
        st.setObject(name, new PGInterval(value.toString()));
      }
    };
  }

  @Override
  public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
    return new BasicExtractor<X>(javaTypeDescriptor, this) {
      @Override
      protected X doExtract(ResultSet rs, String name, WrapperOptions options) throws SQLException {
        return javaTypeDescriptor.wrap(rs.getObject(name), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getObject(index), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getObject(name), options);
      }
    };
  }
}
