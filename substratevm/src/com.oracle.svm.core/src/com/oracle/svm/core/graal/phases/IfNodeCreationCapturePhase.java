package com.oracle.svm.core.src.com.oracle.svm.core.graal.phases;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.phases.BasePhase;


public class IfNodeCreationCapturePhase extends BasePhase<HighTierContext> {
    private final static Map<IfNode, AbstractBeginNode> nodeToTrue = new HashMap<>();
    private final static Map<IfNode, AbstractBeginNode> nodeToFalse = new HashMap<>();
    private static Path path = Paths.get("condition_mapping.txt");

    @Override
    public Optional<NotApplicable> notApplicableTo(jdk.graal.compiler.nodes.GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    private static final Object FILE_LOCK = new Object();

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        synchronized (FILE_LOCK) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {

                for (IfNode ifnode : graph.getNodes(IfNode.TYPE)) {
                    if(ifnode.getNodeSourcePosition() == null){
                        continue;
                    }

                    writer.write("cond: " + ifnode.getNodeSourcePosition().rawToString());
                    writer.newLine();

                    writer.write("true: " + ifnode.trueSuccessor().getNodeSourcePosition().rawToString());
                    writer.newLine();

                    writer.write("false: " + ifnode.falseSuccessor().getNodeSourcePosition().rawToString());
                    writer.newLine();
                    jdk.graal.compiler.nodes.FixedNode lastNode=null;
                    for (jdk.graal.compiler.nodes.FixedNode node : ifnode.falseSuccessor().getBlockNodes()) {
                        lastNode = node;
                    }

                    int lastTrueBCI = getLastBCI(ifnode.trueSuccessor(), ifnode.getNodeSourcePosition());
                    int lastFalseBCI = getLastBCI(ifnode.falseSuccessor(), ifnode.getNodeSourcePosition());
                    if(lastNode!= null && lastNode.getNodeSourcePosition()!=null){
                        writer.write("end: " + (Math.max(lastTrueBCI, lastFalseBCI)));
                        writer.newLine();
                    }
                    writer.write("--------------------------------------");
                    writer.newLine();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    /**
     * Iterates through the block starting at {@code beginNode} and finds the last BCI
     * that belongs to the same method as the original {@code IfNode}.
     */
    private int getLastBCI(AbstractBeginNode beginNode, jdk.graal.compiler.graph.NodeSourcePosition rootPosition) {
        int lastBCI = -1;
        for (jdk.graal.compiler.nodes.FixedNode node : beginNode.getBlockNodes()) {
            jdk.graal.compiler.graph.NodeSourcePosition pos = node.getNodeSourcePosition();

            // Traverse up the caller chain to find the method matching the IfNode
            while (pos != null) {
                if (Objects.equals(pos.getMethod(), rootPosition.getMethod())) {
                    // Update lastBCI whenever we find a node that has a representation
                    // in our target method (even if it's the caller of an inlined method).
                    lastBCI = pos.getBCI();
                    break;
                }
                pos = pos.getCaller();
            }
        }
        return lastBCI;
    }


}
