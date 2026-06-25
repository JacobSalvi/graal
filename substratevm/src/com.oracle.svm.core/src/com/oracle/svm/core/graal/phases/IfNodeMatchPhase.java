package com.oracle.svm.core.src.com.oracle.svm.core.graal.phases;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.tiers.LowTierContext;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.phases.BasePhase;


public class IfNodeMatchPhase extends jdk.graal.compiler.phases.BasePhase<jdk.graal.compiler.phases.tiers.LowTierContext> {
    @Override
    protected void run(StructuredGraph graph, LowTierContext context) {
        for(IfNode ifnode : graph.getNodes(IfNode.TYPE)) {
            AbstractBeginNode falseSuccessor = ifnode.falseSuccessor();
            AbstractBeginNode trueSuccessor = ifnode.trueSuccessor();
//            System.out.println(ifnode.getNodeSourcePosition());
        }
    }
}
