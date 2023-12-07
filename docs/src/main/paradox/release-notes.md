# Release Notes

## 1.0.0

Apache Pekko Persistence R2DBC 1.0.0 is based on Akka Persistence R2DBC 0.7.7. Pekko came about as a result of Lightbend's
decision to make future Akka releases under a [Business Software License](https://www.lightbend.com/blog/why-we-are-changing-the-license-for-akka),
a license that is not compatible with Open Source usage.

Apache Pekko has changed the package names, among other changes. Config names have changed to use `pekko` instead
of `akka` in their names. Users switching from Akka to Pekko should read our [Migration Guide](https://pekko.apache.org/docs/pekko/current/project/migration-guides.html).

Generally, we have tried to make it as easy as possible to switch existing Akka based projects over to using Pekko.

We have gone through the code base and have tried to properly acknowledge all third party source code in the
Apache Pekko code base. If anyone believes that there are any instances of third party source code that is not
properly acknowledged, please get in touch.

### Bug Fixes

We haven't had to fix any significant bugs that were in Akka Persistence R2DBC 0.7.7.

### Changes

* Changed the table names in the DDL schemas to remove the akka/pekko prefixes ([PR71](https://github.com/apache/incubator-pekko-persistence-r2dbc/pull/71))

### Additions

* Scala 3 support
    * the minimum required version is Scala 3.3.0

### Dependency Upgrades
We have tried to limit the changes to third party dependencies that are used in Pekko Persistence R2DBC 0.7.7. These are some exceptions:

* some minor upgrades to r2dbc jars (all still 0.9.x)
* scalatest 3.2.14. Pekko users who have existing tests based on Akka Testkit may need to migrate their tests due to the scalatest upgrade. The [scalatest 3.2 release notes](https://www.scalatest.org/release_notes/3.2.0) have a detailed description of the changes needed.
