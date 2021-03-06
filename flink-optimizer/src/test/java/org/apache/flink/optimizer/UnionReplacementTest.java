/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.optimizer;

import junit.framework.Assert;
import org.apache.flink.api.common.operators.util.FieldList;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.common.Plan;
import org.apache.flink.api.java.io.DiscardingOutputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.optimizer.plan.Channel;
import org.apache.flink.optimizer.plan.NAryUnionPlanNode;
import org.apache.flink.optimizer.plan.OptimizedPlan;
import org.apache.flink.optimizer.plan.SingleInputPlanNode;
import org.apache.flink.optimizer.plantranslate.JobGraphGenerator;
import org.apache.flink.optimizer.util.CompilerTestBase;
import org.apache.flink.runtime.operators.shipping.ShipStrategyType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

@SuppressWarnings("serial")
public class UnionReplacementTest extends CompilerTestBase {

	@Test
	public void testUnionReplacement() {
		try {
			ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
			DataSet<String> input1 = env.fromElements("test1");
			DataSet<String> input2 = env.fromElements("test2");
	
			DataSet<String> union = input1.union(input2);
	
			union.output(new DiscardingOutputFormat<String>());
			union.output(new DiscardingOutputFormat<String>());
	
			Plan plan = env.createProgramPlan();
			OptimizedPlan oPlan = compileNoStats(plan);
			JobGraphGenerator jobGen = new JobGraphGenerator();
			jobGen.compileJobGraph(oPlan);
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	/**
	 *
	 * Test for FLINK-2662.
	 *
	 * Checks that a plan with an union with two outputs is correctly translated.
	 * The program can be illustrated as follows:
	 *
	 * Src1 ----------------\
	 *                       >-> Union123 -> GroupBy(0) -> Sum -> Output
	 * Src2 -\              /
	 *        >-> Union23--<
	 * Src3 -/              \
	 *                       >-> Union234 -> GroupBy(1) -> Sum -> Output
	 * Src4 ----------------/
	 *
	 * The fix for FLINK-2662 translates the union with two output (Union-23) into two separate
	 * unions (Union-23_1 and Union-23_2) with one output each. Due to this change, the interesting
	 * partitioning properties for GroupBy(0) and GroupBy(1) are pushed through Union-23_1 and
	 * Union-23_2 and do not interfere with each other (which would be the case if Union-23 would
	 * be a single operator with two outputs).
	 *
	 */
	@Test
	public void testUnionWithTwoOutputsTest() throws Exception {

		// -----------------------------------------------------------------------------------------
		// Build test program
		// -----------------------------------------------------------------------------------------

		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(DEFAULT_PARALLELISM);

		DataSet<Tuple2<Long, Long>> src1 = env.fromElements(new Tuple2<>(0L, 0L));
		DataSet<Tuple2<Long, Long>> src2 = env.fromElements(new Tuple2<>(0L, 0L));
		DataSet<Tuple2<Long, Long>> src3 = env.fromElements(new Tuple2<>(0L, 0L));
		DataSet<Tuple2<Long, Long>> src4 = env.fromElements(new Tuple2<>(0L, 0L));

		DataSet<Tuple2<Long, Long>> union23 = src2.union(src3);
		DataSet<Tuple2<Long, Long>> union123 = src1.union(union23);
		DataSet<Tuple2<Long, Long>> union234 = src4.union(union23);

		union123.groupBy(0).sum(1).name("1").output(new DiscardingOutputFormat<Tuple2<Long, Long>>());
		union234.groupBy(1).sum(0).name("2").output(new DiscardingOutputFormat<Tuple2<Long, Long>>());

		// -----------------------------------------------------------------------------------------
		// Verify optimized plan
		// -----------------------------------------------------------------------------------------

		OptimizedPlan optimizedPlan = compileNoStats(env.createProgramPlan());

		OptimizerPlanNodeResolver resolver = getOptimizerPlanNodeResolver(optimizedPlan);

		SingleInputPlanNode groupRed1 = resolver.getNode("1");
		SingleInputPlanNode groupRed2 = resolver.getNode("2");

		// check partitioning is correct
		Assert.assertTrue("Reduce input should be partitioned on 0.",
			groupRed1.getInput().getGlobalProperties().getPartitioningFields().isExactMatch(new FieldList(0)));
		Assert.assertTrue("Reduce input should be partitioned on 1.",
			groupRed2.getInput().getGlobalProperties().getPartitioningFields().isExactMatch(new FieldList(1)));

		// check group reduce inputs are n-ary unions with three inputs
		Assert.assertTrue("Reduce input should be n-ary union with three inputs.",
			groupRed1.getInput().getSource() instanceof NAryUnionPlanNode &&
				((NAryUnionPlanNode) groupRed1.getInput().getSource()).getListOfInputs().size() == 3);
		Assert.assertTrue("Reduce input should be n-ary union with three inputs.",
			groupRed2.getInput().getSource() instanceof NAryUnionPlanNode &&
				((NAryUnionPlanNode) groupRed2.getInput().getSource()).getListOfInputs().size() == 3);

		// check channel from union to group reduce is forwarding
		Assert.assertTrue("Channel between union and group reduce should be forwarding",
			groupRed1.getInput().getShipStrategy().equals(ShipStrategyType.FORWARD));
		Assert.assertTrue("Channel between union and group reduce should be forwarding",
			groupRed2.getInput().getShipStrategy().equals(ShipStrategyType.FORWARD));

		// check that all inputs of unions are hash partitioned
		List<Channel> union123In = ((NAryUnionPlanNode) groupRed1.getInput().getSource()).getListOfInputs();
		for(Channel i : union123In) {
			Assert.assertTrue("Union input channel should hash partition on 0",
				i.getShipStrategy().equals(ShipStrategyType.PARTITION_HASH) &&
					i.getShipStrategyKeys().isExactMatch(new FieldList(0)));
		}
		List<Channel> union234In = ((NAryUnionPlanNode) groupRed2.getInput().getSource()).getListOfInputs();
		for(Channel i : union234In) {
			Assert.assertTrue("Union input channel should hash partition on 0",
				i.getShipStrategy().equals(ShipStrategyType.PARTITION_HASH) &&
					i.getShipStrategyKeys().isExactMatch(new FieldList(1)));
		}

	}
}
