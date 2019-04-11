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
package com.facebook.presto.hive;

import com.facebook.presto.tests.AbstractTestAggregations;
import org.testng.annotations.Test;

import static com.facebook.presto.hive.HiveQueryRunner.createMaterializingQueryRunner;
import static com.facebook.presto.hive.TestHiveIntegrationSmokeTest.assertRemoteMaterializedExchangesCount;
import static io.airlift.tpch.TpchTable.getTables;

public class TestHiveDistributedAggregationsWithExchangeMaterialization
        extends AbstractTestAggregations
{
    public TestHiveDistributedAggregationsWithExchangeMaterialization()
    {
        super(() -> createMaterializingQueryRunner(getTables()));
    }

    @Test
    public void testMaterializedExchangesEnabled()
    {
        assertQuery(getSession(), "SELECT orderkey, COUNT(*) lines FROM lineitem GROUP BY orderkey", assertRemoteMaterializedExchangesCount(1));
    }

    @Override
    public void testExtractDistinctAggregationOptimizer()
    {
        // row type is not supported by the Hive hash code function
    }

    @Override
    public void testGroupByRow()
    {
        // row type is not supported by the Hive hash code function
    }

    @Override
    public void testApproximateCountDistinctGroupBy()
    {
        // Unsupported Hive type: HyperLogLog
    }

    @Override
    public void testApproximateCountDistinctGroupByWithStandardError()
    {
        // Unsupported Hive type: HyperLogLog
    }

    @Override
    public void testAggregationPushedBelowOuterJoin()
    {
        // Anonymous row type is not supported in Hive
    }
}