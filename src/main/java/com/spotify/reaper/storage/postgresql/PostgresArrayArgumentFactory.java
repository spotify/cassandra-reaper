/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.storage.postgresql;

import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.Argument;
import org.skife.jdbi.v2.tweak.ArgumentFactory;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Provides JDBI a method to map String Collection to an SQL Array type.
 *
 * NOTICE: this is very non-generic and ugly due to not being able to have different generic types
 * except Strings when using Collections with JDBI here.
 * Should probably use own collection types without generics to solve this.
 * See LongCollectionSQLType for example, if this becomes a problem.
 */
public class PostgresArrayArgumentFactory implements ArgumentFactory<Collection<String>> {

  @Override
  public boolean accepts(Class<?> expectedType, Object value, StatementContext ctx) {
    return value instanceof Collection;
  }

  @Override
  public Argument build(Class<?> expectedType, final Collection<String> value,
                        StatementContext ctx) {
    return new Argument() {
      public void apply(int position, PreparedStatement statement, StatementContext ctx)
          throws SQLException {
        try {
          Array sqlArray = ctx.getConnection().createArrayOf("text", value.toArray());
          statement.setArray(position, sqlArray);
        } catch(SQLException e) {
          // H2 DB feature not supported: "createArray" error
          if(e.getErrorCode() != 50100) throw e;
          statement.setObject(position, value.toArray());
        }
      }
    };
  }
}
