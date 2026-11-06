/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.internal

import org.apache.pekko
import pekko.persistence.r2dbc.Dialect
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SqlSpec extends AnyWordSpec with TestSuite with Matchers {

  "SQL string interpolation" should {
    import Sql.Interpolation

    "replace ? bind parameters with numbered $ (avoiding escaped ones)" in {
      sql"select * from bar where a = ? and qa = 'Question?? Answer!'" shouldBe
      "select * from bar where a = $1 and qa = 'Question? Answer!'"
      sql"select * from bar where a = ? and b = ? and jsonb ?? 'status' and c = ?" shouldBe
      "select * from bar where a = $1 and b = $2 and jsonb ? 'status' and c = $3"
      sql"select * from bar" shouldBe "select * from bar"
    }

    "work together with standard string interpolation" in {
      val table = "foo"
      sql"select * from $table where a = ?" shouldBe "select * from foo where a = $1"
    }

    "replace bind parameters after standard string interpolation" in {
      val where = "where a = ? and b = ?"
      sql"select * from foo $where" shouldBe "select * from foo where a = $1 and b = $2"
    }

    "keep ? bind parameters for the MySQL and MariaDB dialects" in {
      val sql = "select * from foo where a = ? and b = ?"
      Sql.DialectOps(Dialect.MariaDB).replaceParameters(sql) shouldBe sql
      Sql.DialectOps(Dialect.MySQL).replaceParameters(sql) shouldBe sql
      Sql.DialectOps(Dialect.Postgres).replaceParameters(sql) shouldBe "select * from foo where a = $1 and b = $2"
    }

    "trim line breaks" in {
      val table = "foo"
      sql"""
        select * from $table where
          a = ? and
          b = ?
        """ shouldBe "select * from foo where a = $1 and b = $2"
    }
  }

  "SQL string interpolation for Oracle" should {
    import Sql.DialectInterpolation
    implicit val dialect: Dialect = Dialect.Oracle

    "keep ? bind parameters" in {
      sql"select * from bar where a = ? and b = ?" shouldBe "select * from bar where a = ? and b = ?"
    }

    "replace a trailing LIMIT with FETCH FIRST" in {
      sql"""
        select * from bar where a = ?
        order by b
        LIMIT ?
        """ shouldBe "select * from bar where a = ? order by b FETCH FIRST ? ROWS ONLY"
      sql"select * from bar order by b limit 1" shouldBe "select * from bar order by b FETCH FIRST 1 ROWS ONLY"
    }

    "not replace LIMIT that isn't at the end" in {
      sql"select limit_col from bar where a = ?" shouldBe "select limit_col from bar where a = ?"
      sql"select * from bar where note = 'LIMIT ?' and a = ?" shouldBe
      "select * from bar where note = 'LIMIT ?' and a = ?"
    }
  }

  "SQL string interpolation for Postgres" should {
    import Sql.DialectInterpolation
    implicit val dialect: Dialect = Dialect.Postgres

    "keep LIMIT" in {
      sql"select * from bar order by b LIMIT ?" shouldBe "select * from bar order by b LIMIT $1"
    }
  }

}
