/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.persistence.r2dbc.journal.mysql

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MySQLTagsJsonSpec extends AnyWordSpec with Matchers {

  "MySQLJournalDao.tagsToJson" should {

    "serialise an empty set to an empty JSON array" in {
      MySQLJournalDao.tagsToJson(Set.empty) shouldBe "[]"
    }

    "serialise a single tag to a compact JSON array" in {
      MySQLJournalDao.tagsToJson(Set("tag1")) shouldBe """["tag1"]"""
    }

    "produce compact JSON (no extra whitespace)" in {
      val json = MySQLJournalDao.tagsToJson(Set("a", "b", "c"))
      json should startWith("[")
      json should endWith("]")
      json should not include " "
    }

    "escape double-quotes in tag values" in {
      val json = MySQLJournalDao.tagsToJson(Set("""say "hello""""))
      json shouldBe """["say \"hello\""]"""
    }

    "escape backslashes in tag values" in {
      val json = MySQLJournalDao.tagsToJson(Set("""path\to\file"""))
      json shouldBe """["path\\to\\file"]"""
    }

    "round-trip through tagsFromJson" in {
      val tags = Set("alpha", "beta", "gamma")
      MySQLJournalDao.tagsFromJson(MySQLJournalDao.tagsToJson(tags)) shouldBe tags
    }
  }

  "MySQLJournalDao.tagsFromJson" should {

    "return an empty set for null input" in {
      MySQLJournalDao.tagsFromJson(null) shouldBe Set.empty
    }

    "return an empty set for an empty JSON array '[]'" in {
      MySQLJournalDao.tagsFromJson("[]") shouldBe Set.empty
    }

    "return an empty set for an empty JSON array with spaces '[ ]'" in {
      MySQLJournalDao.tagsFromJson("[ ]") shouldBe Set.empty
    }

    "return an empty set for an empty JSON array with leading/trailing whitespace '  []  '" in {
      MySQLJournalDao.tagsFromJson("  []  ") shouldBe Set.empty
    }

    "return an empty set for an empty array with internal whitespace '[\n]'" in {
      MySQLJournalDao.tagsFromJson("[\n]") shouldBe Set.empty
    }

    "parse a single-element array" in {
      MySQLJournalDao.tagsFromJson("""["tag1"]""") shouldBe Set("tag1")
    }

    "parse a multi-element array" in {
      MySQLJournalDao.tagsFromJson("""["alpha","beta","gamma"]""") shouldBe Set("alpha", "beta", "gamma")
    }

    "parse a multi-element array with spaces after commas" in {
      MySQLJournalDao.tagsFromJson("""["alpha", "beta", "gamma"]""") shouldBe Set("alpha", "beta", "gamma")
    }

    "parse tags that contain escaped double-quotes" in {
      MySQLJournalDao.tagsFromJson("""["say \"hello\""]""") shouldBe Set("""say "hello"""")
    }

    "parse tags that contain escaped backslashes" in {
      MySQLJournalDao.tagsFromJson("""["path\\to\\file"]""") shouldBe Set("""path\to\file""")
    }

    "throw IllegalStateException when input is not a JSON array (plain string)" in {
      an[IllegalStateException] should be thrownBy {
        MySQLJournalDao.tagsFromJson("notanarray")
      }
    }

    "throw IllegalStateException when input is a JSON object" in {
      an[IllegalStateException] should be thrownBy {
        MySQLJournalDao.tagsFromJson("""{"tag":"value"}""")
      }
    }

    "throw IllegalStateException when array contains a non-string element (number)" in {
      an[IllegalStateException] should be thrownBy {
        MySQLJournalDao.tagsFromJson("""["valid", 42]""")
      }
    }

    "throw IllegalStateException when array contains a non-string element (boolean)" in {
      an[IllegalStateException] should be thrownBy {
        MySQLJournalDao.tagsFromJson("""["valid", true]""")
      }
    }

    "throw IllegalStateException when string is unterminated" in {
      an[IllegalStateException] should be thrownBy {
        MySQLJournalDao.tagsFromJson("""["unterminated""")
      }
    }
  }
}
