package com.oracle.svm.core.src.com.oracle.svm.core.graal.phases;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class LoopBeginCapturePhase extends jdk.graal.compiler.phases.BasePhase<jdk.graal.compiler.phases.tiers.HighTierContext> {
    private final static Map<jdk.graal.compiler.nodes.IfNode, jdk.graal.compiler.nodes.AbstractBeginNode> nodeToTrue = new HashMap<>();
    private final static Map<jdk.graal.compiler.nodes.IfNode, jdk.graal.compiler.nodes.AbstractBeginNode> nodeToFalse = new HashMap<>();
    private static Path path = Paths.get("loop_begin.txt");

    @Override
    public Optional<NotApplicable> notApplicableTo(jdk.graal.compiler.nodes.GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }


    @Override
    protected void run(jdk.graal.compiler.nodes.StructuredGraph graph, jdk.graal.compiler.phases.tiers.HighTierContext context) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

            for (jdk.graal.compiler.nodes.LoopBeginNode lb : graph.getNodes(jdk.graal.compiler.nodes.LoopBeginNode.TYPE)) {
                int uf = lb.getUnrollFactor();
                int p = lb.peelings();

                writer.write(String.format("%s [%d, %d]", lb.getNodeSourcePosition().rawToString(), uf, p));
                writer.newLine();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}

