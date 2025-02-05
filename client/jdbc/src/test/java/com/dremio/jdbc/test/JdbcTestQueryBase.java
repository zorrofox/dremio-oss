/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
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
package com.dremio.jdbc.test;

import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.rules.TestRule;

import com.dremio.common.util.TestTools;
import com.dremio.jdbc.DremioResultSet;
import com.dremio.jdbc.JdbcWithServerTestBase;
import com.google.common.base.Stopwatch;

public class JdbcTestQueryBase extends JdbcWithServerTestBase {
  // Set a timeout unless we're debugging.
  @Rule
  public final TestRule timeoutRule = TestTools.getTimeoutRule(40, TimeUnit.SECONDS);

  protected static final String WORKING_PATH;
  static{
    WORKING_PATH = Paths.get("").toAbsolutePath().toString();
  }

  protected static void testQuery(String sql) throws Exception{
    boolean success = false;
    try {
      for (int x = 0; x < 1; x++) {
        Stopwatch watch = Stopwatch.createStarted();
        Statement s = getConnection().createStatement();
        ResultSet r = s.executeQuery(sql);
        System.out.println(String.format("QueryId: %s", r.unwrap(DremioResultSet.class).getQueryId()));
        boolean first = true;
        while (r.next()) {
          ResultSetMetaData md = r.getMetaData();
          if (first == true) {
            for (int i = 1; i <= md.getColumnCount(); i++) {
              System.out.print(md.getColumnName(i));
              System.out.print('\t');
            }
            System.out.println();
            first = false;
          }

          for (int i = 1; i <= md.getColumnCount(); i++) {
            System.out.print(r.getObject(i));
            System.out.print('\t');
          }
          System.out.println();
        }

        System.out.println(String.format("Query completed in %d millis.", watch.elapsed(TimeUnit.MILLISECONDS)));
      }

      System.out.println("\n\n\n");
      success = true;
    } finally {
      if (!success) {
        Thread.sleep(2000);
      }
    }
  }
}
