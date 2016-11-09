/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.performance.benchmarks;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.google.common.collect.Multimap;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.performance.benchmarks.table.RegeneratingTable;

@State(Scope.Benchmark)
public class KvsDeleteBenchmarks {

    private Object doDelete(RegeneratingTable<Multimap<Cell, Long>> table) {
        table.getKvs().delete(table.getTableRef(), table.getTableCells());
        return table.getTableCells();
    }

    @Benchmark
    @Warmup(time = 2, timeUnit = TimeUnit.SECONDS)
    @Measurement(time = 10, timeUnit = TimeUnit.SECONDS)
    public Object singleDelete(RegeneratingTable.KvsRowRegeneratingTable table) {
        return doDelete(table);
    }

    @Benchmark
    @Warmup(time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(time = 15, timeUnit = TimeUnit.SECONDS)
    public Object batchDelete(RegeneratingTable.KvsBatchRegeneratingTable table) {
        return doDelete(table);
    }

}
