/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter

import org.neo4j.cypher.internal.compiler.helpers.LogicalPlanBuilder
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.logical.plans.Descending
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class BfsOrdererTest extends CypherFunSuite with LogicalPlanningTestSupport {

  /**
   * Sort Positive Cases
   */

  test("should rewrite to partial sort when depth is first sort column in asc order") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth"), Ascending("to")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialSort(Seq(Ascending("depth")), Seq(Ascending("to")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should remove sort when depth is only sort column and in asc order") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should rewrite to partial sort when two bfs plans and sort uses depth of first one") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth1"), Ascending("to")))
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialSort(Seq(Ascending("depth1")), Seq(Ascending("to")))
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should rewrite to partial sort when sort column is an alias of original depth variable") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth1Alias2"), Ascending("to")))
      .projection("depth1Alias AS depth1Alias2", "depth1Alias AS depth1Alias3")
      .projection("depth1 AS depth1Alias", "depth2 AS depth2Alias")
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialSort(Seq(Ascending("depth1Alias2")), Seq(Ascending("to")))
      .projection("depth1Alias AS depth1Alias2", "depth1Alias AS depth1Alias3")
      .projection("depth1 AS depth1Alias", "depth2 AS depth2Alias")
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  /**
   * Sort Negative Cases
   */

  test("should not rewrite to partial sort when depth is not first sort column") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("to"), Ascending("depth")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite to partial sort when depth is first sort column but is desc") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Descending("depth"), Ascending("to")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not remove sort when depth is only sort column but is desc") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Descending("depth")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite to partial sort when two bfs plans and sort uses depth of second one") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth2"), Ascending("to")))
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite when there is another sort between bfs and depth-sorting sort") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth")))
      .sort(Seq(Ascending("to")))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  // technically this should be fine to rewrite, but the rewriter is conservative
  test("should not rewrite when there is a distinct between bfs and sort") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth")))
      .distinct("to AS to", "depth AS depth")
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite when there is an aggregation between bfs and sort") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .sort(Seq(Ascending("depth")))
      .aggregation(Seq("to AS to", "depth AS depth"), Seq("count(*) AS count"))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  /**
   * Top Positive Cases
   */

  test("should rewrite to partial top when depth is first top column in asc order") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth"), Ascending("to")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialTop(Seq(Ascending("depth")), Seq(Ascending("to")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should remove top when depth is only top column and in asc order") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .limit(42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should rewrite to partial top when two bfs plans and top uses depth of first one") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth1"), Ascending("to")), 42)
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialTop(Seq(Ascending("depth1")), Seq(Ascending("to")), 42)
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("should rewrite to partial top when sort column is an alias of original depth variable") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth1Alias2"), Ascending("to")), 42)
      .projection("depth1Alias AS depth1Alias2", "depth1Alias AS depth1Alias3")
      .projection("depth1 AS depth1Alias", "depth2 AS depth2Alias")
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .partialTop(Seq(Ascending("depth1Alias2")), Seq(Ascending("to")), 42)
      .projection("depth1Alias AS depth1Alias2", "depth1Alias AS depth1Alias3")
      .projection("depth1 AS depth1Alias", "depth2 AS depth2Alias")
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    rewrite(before) should equal(after)
  }

  /**
   * Top Negative Cases
   */

  test("should not rewrite to partial top when depth is not first top column") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("to"), Ascending("depth")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite to partial top when depth is first top column but is desc") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Descending("depth"), Ascending("to")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not remove top when depth is only top column but is desc") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Descending("depth")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite to partial top when two bfs plans and top uses depth of second one") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth2"), Ascending("to")), 42)
      .bfsPruningVarExpand("(middle)-[*1..3]-(to)", depthName = Some("depth2"))
      .bfsPruningVarExpand("(from)-[*1..3]-(middle)", depthName = Some("depth1"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite when there is another top between bfs and depth-sorting sort") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth")), 42)
      .top(Seq(Ascending("to")), 42)
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  // technically this should be fine to rewrite, but the rewriter is conservative
  test("should not rewrite when there is a distinct between bfs and top") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth")), 42)
      .distinct("to AS to", "depth AS depth")
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  test("should not rewrite when there is an aggregation between bfs and top") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .top(Seq(Ascending("depth")), 42)
      .aggregation(Seq("to AS to", "depth AS depth"), Seq("count(*) AS count"))
      .bfsPruningVarExpand("(from)-[*1..3]-(to)", depthName = Some("depth"))
      .argument("from")
      .build()

    assertNotRewritten(before)
  }

  private def assertNotRewritten(p: LogicalPlan): Unit = {
    rewrite(p) should equal(p)
  }

  private def rewrite(p: LogicalPlan): LogicalPlan =
    p.endoRewrite(bfsDepthOrderer)
}
