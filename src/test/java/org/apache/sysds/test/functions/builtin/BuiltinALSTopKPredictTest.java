/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.functions.builtin;

import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BuiltinALSTopKPredictTest extends AutomatedTestBase {
    private final static String TEST_NAME = "ALS_topk_predict";
    private final static String TEST_DIR = "functions/builtin/";
    private static final String TEST_CLASS_DIR = TEST_DIR + BuiltinALSTopKPredictTest.class.getSimpleName() + "/";

    @Override
    public void setUp() {
        addTestConfiguration(TEST_NAME,new TestConfiguration(TEST_CLASS_DIR, TEST_NAME,new String[]{"B"}));
    }

    @Test
    public void testALSTopKPredict() {
        runtestALSTopKPredict();
    }

    private void runtestALSTopKPredict(){

        loadTestConfiguration(getTestConfiguration(TEST_NAME));
        String HOME = SCRIPT_DIR + TEST_DIR;
        fullDMLScriptName = HOME + TEST_NAME + ".dml";
        List<String> proArgs = new ArrayList<>();

        proArgs.add("-explain");
        proArgs.add("-stats");
        proArgs.add("-args");
        proArgs.add(input("X"));
        proArgs.add(input("L"));
        proArgs.add(input("R"));
        proArgs.add(input("V"));
        proArgs.add(output("V_top_indices"));
        proArgs.add(output("V_top_values"));
        programArgs = proArgs.toArray(new String[proArgs.size()]);

        double[][] X = {{1, 2, 3, 4, 5}};
        writeInputMatrixWithMTD("X", X, true);

        double[][] L = {{1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}};
        writeInputMatrixWithMTD("L", L, true);

        double[][] R = {{1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}};
        writeInputMatrixWithMTD("R", R, true);

        double[][] V = {{1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}};
        writeInputMatrixWithMTD("V", V, true);

        runTest(true, EXCEPTION_NOT_EXPECTED, null, -1);
    }

}
