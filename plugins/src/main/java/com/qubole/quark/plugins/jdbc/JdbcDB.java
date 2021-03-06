/*
 * Copyright (c) 2015. Qubole Inc
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
 *    limitations under the License.
 */

package com.qubole.quark.plugins.jdbc;

import org.apache.calcite.runtime.ResultSetEnumerable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;

import org.apache.commons.lang.Validate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.qubole.quark.QuarkException;

import com.qubole.quark.planner.QuarkColumn;
import com.qubole.quark.planner.QuarkTable;
import com.qubole.quark.plugins.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import java.util.Iterator;
import java.util.Map;

import javax.sql.DataSource;
/**
 * Contains common functions for all Data sources that support JDBC.
 */
public abstract class JdbcDB implements Executor {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcDB.class);

  public abstract Connection getConnection() throws ClassNotFoundException, SQLException;
  protected abstract String getCatalogSql();
  protected abstract ImmutableMap<String, String> getDataTypes();

  protected final String url;
  protected final String user;
  protected final String password;

  JdbcDB(Map<String, Object> properties) {
    validate(properties);
    this.url = (String) properties.get("url");
    this.user = (String) properties.get("username");
    this.password = (String) properties.get("password");
  }

  private void validate(Map<String, Object> properties) {
    Validate.notNull(properties.get("url"), "Field \"url\" specifying JDBC endpoint needs "
        + "to be defined for JDBC Data Source in JSON");
    Validate.notNull(properties.get("username"), "Field \"username\" specifying username needs "
        + "to be defined for JDBC Data Source in JSON");
    Validate.notNull(properties.get("password"), "Field \"password\" specifying password "
        + "to be defined for JDBC Data Source in JSON");
  }

  public ImmutableMap<String, Schema> getSchemas() throws QuarkException {
    Connection conn = null;
    Statement stmt = null;
    try {
      conn = this.getConnection();
      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(this.getCatalogSql());
      ImmutableMap<String, Schema> schemaMap = getSchemaFromResultSet(rs);
      rs.close();
      return schemaMap;
    } catch (ClassNotFoundException | SQLException s) {
      throw new QuarkException(s.getCause());
    } finally {
      try {
        if (stmt != null) {
          stmt.close();
        }

        if (conn != null) {
          conn.close();
        }
      } catch (SQLException e) {
        LOG.error("Exception thrown while closing connection to "
            + this.url + " " + e.getMessage(), e);
      }
    }
  }

  //Assuming rs.getString(1) => schemaname, rs.getString(2) => tableName,
  // rs.getString(3) => columnName, rs.getString(4) => columnType
  private ImmutableMap<String, Schema> getSchemaFromResultSet(ResultSet rs)
      throws SQLException {
    if (rs == null || !rs.next()) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Schema> schemaBuilder = new ImmutableMap.Builder<>();

    ImmutableMap<String, String> dataTypes = this.getDataTypes();
    while (!rs.isAfterLast()) {
      String currentSchema = rs.getString(1);
      ImmutableMap.Builder<String, Table> tableBuilder = new ImmutableMap.Builder<>();
      while (!rs.isAfterLast() && rs.getString(1).equals(currentSchema)) {
        ImmutableList.Builder<QuarkColumn> columnBuilder =
            new ImmutableList.Builder<>();
        String currentTable = rs.getString(2);
        while (rs.getString(2).equals(currentTable)) {
          String dataType = rs.getString(4);
          for (String key: dataTypes.keySet()) {
            if (rs.getString(4).matches(key)) {
              dataType = dataTypes.get(key);
              break;
            }
          }
          String columnName = rs.getString(3);
          if (!this.isCaseSensitive()) {
            columnName = columnName.toUpperCase();
          }
          columnBuilder.add(new QuarkColumn(columnName, dataType));
          LOG.debug("Adding column:  " + rs.getString(1) + " : " + rs.getString(2)
              + " : " + rs.getString(3) + " : " + rs.getString(4));
          if (!rs.next()) {
            break;
          }
        }

        if (!this.isCaseSensitive()) {
          currentTable = currentTable.toUpperCase();
        }
        tableBuilder.put(currentTable, new QuarkTable(columnBuilder.build()));
      }
      if (!this.isCaseSensitive()) {
        currentSchema = currentSchema.toUpperCase();
      }

      schemaBuilder.put(currentSchema,
          new com.qubole.quark.plugins.SimpleSchema(currentSchema.toUpperCase(),
              tableBuilder.build()));
    }
    return schemaBuilder.build();
  }

  public Iterator<Object> executeQuery(final String sql)
      throws Exception {
    cleanup();
    return execute(this.getConnection(), sql);
  }

  public Iterator<Object> execute(final Connection conn, final String sql)
      throws Exception {
    return ResultSetEnumerable.of(
        new DataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            return conn;
          }

          @Override
          public Connection getConnection(String s, String s1)
              throws SQLException {
            return conn;
          }

          @Override
          public PrintWriter getLogWriter() throws SQLException {
            return null;
          }

          @Override
          public void setLogWriter(PrintWriter printWriter)
              throws SQLException {

          }

          @Override
          public void setLoginTimeout(int i) throws SQLException {

          }

          @Override
          public int getLoginTimeout() throws SQLException {
            return 100;
          }

          @Override
          public java.util.logging.Logger getParentLogger()
              throws SQLFeatureNotSupportedException {
            return null;
          }

          @Override
          public <T> T unwrap(Class<T> aClass) throws SQLException {
            return null;
          }

          @Override
          public boolean isWrapperFor(Class<?> aClass) throws SQLException {
            return false;
          }
        },
        sql
      ).iterator();
  }


  /**
   * Cleans up the JDBC resource without waiting for the Garbage Collection
   *
   * @throws SQLException
   */
  @Override
  public void cleanup() throws SQLException {
  }

  @Override
  public boolean isCaseSensitive() {
    return false;
  }
}
