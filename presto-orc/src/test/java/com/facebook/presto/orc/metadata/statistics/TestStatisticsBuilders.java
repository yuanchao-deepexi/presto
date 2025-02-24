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
package com.facebook.presto.orc.metadata.statistics;

import com.facebook.presto.common.type.Type;
import com.facebook.presto.orc.ColumnWriterOptions;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.OrcType;
import org.testng.annotations.Test;

import java.util.function.Supplier;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.RealType.REAL;
import static com.facebook.presto.common.type.SmallintType.SMALLINT;
import static com.facebook.presto.common.type.TinyintType.TINYINT;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class TestStatisticsBuilders
{
    @Test
    public void testCreateStatisticsBuilderValidType()
    {
        assertTrue(createStatisticsBuilder(TINYINT) instanceof CountStatisticsBuilder);
        assertTrue(createStatisticsBuilder(SMALLINT) instanceof IntegerStatisticsBuilder);
        assertTrue(createStatisticsBuilder(INTEGER) instanceof IntegerStatisticsBuilder);
        assertTrue(createStatisticsBuilder(BIGINT) instanceof IntegerStatisticsBuilder);
        assertTrue(createStatisticsBuilder(VARCHAR) instanceof StringStatisticsBuilder);
        assertTrue(createStatisticsBuilder(VARBINARY) instanceof BinaryStatisticsBuilder);
    }

    @Test
    public void testCreateStatisticsBuilderInvalidType()
    {
        assertThrows(IllegalArgumentException.class, () -> createStatisticsBuilder(REAL));
    }

    private static StatisticsBuilder createStatisticsBuilder(Type type)
    {
        ColumnWriterOptions columnWriterOptions = ColumnWriterOptions.builder().setCompressionKind(CompressionKind.ZSTD).build();
        OrcType orcType = OrcType.toOrcType(0, type).get(0);
        Supplier<? extends StatisticsBuilder> supplier = StatisticsBuilders.createFlatMapKeyStatisticsBuilderSupplier(orcType, columnWriterOptions);
        return supplier.get();
    }
}
