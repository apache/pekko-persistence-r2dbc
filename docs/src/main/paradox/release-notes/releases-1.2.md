<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Release Notes (1.2.x)

Apache Pekko Persistence R2DBC 1.2.x releases support Java 8 and above.

## 1.2.0

Release notes for Apache Pekko Persistence R2DBC 1.2.0. See [GitHub Milestone for 1.2.0](https://github.com/apache/pekko-persistence-r2dbc/milestone/5?closed=1) for a fuller list of changes.

### Bug Fix

* Fix incorrect implementation of Durable State deleteObject(id, revision) ([PR384](https://github.com/apache/pekko-persistence-r2dbc/pull/384))
