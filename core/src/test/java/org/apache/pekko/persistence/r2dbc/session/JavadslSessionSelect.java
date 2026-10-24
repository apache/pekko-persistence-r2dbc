/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.persistence.r2dbc.session;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.persistence.r2dbc.session.javadsl.R2dbcSession;

import io.r2dbc.spi.Row;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class JavadslSessionSelect {

  private JavadslSessionSelect() {}

  // a java.util.function.Function value, not a lambda written at the call site
  private static final Function<Row, String> MAP_ROW = row -> row.get("v", String.class);

  private static final String SQL = "SELECT 'a' AS v";

  public static CompletionStage<Optional<String>> selectOne(ActorSystem<?> system) {
    return R2dbcSession.withSession(
        system, session -> session.selectOne(session.createStatement(SQL), MAP_ROW));
  }

  public static CompletionStage<List<String>> select(ActorSystem<?> system) {
    return R2dbcSession.withSession(
        system, session -> session.select(session.createStatement(SQL), MAP_ROW));
  }
}
