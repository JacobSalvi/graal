package com.oracle.svm.core.src.com.oracle.svm.core.graal.phases;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import jdk.graal.compiler.bytecode.Bytecode;
import jdk.graal.compiler.bytecode.BytecodeStream;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.java.BciBlockMapping;
import jdk.graal.compiler.java.BciBlockMapping.BciBlock;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Writes, for every IfNode, the source position of the condition and of both
 * successors, plus the bytecode region each successor owns.
 *
 * The regions are the point of this phase. A consumer that only knows where each
 * successor starts has to guess where it ends, and the usual guess -- the true
 * region runs until the false region begins -- is wrong for any branch whose body
 * is not one contiguous stretch of bytecode: break, continue, an early return, a
 * throw, or a short circuit && all break it. Measured against a real profile that
 * guess placed 75% of observed branch targets in neither region.
 *
 * Regions are derived from {@link BciBlockMapping} rather than from the graph, for
 * two reasons:
 *
 *   - The graph has already been through some canonicalisation by the time this
 *     phase runs, so a successor's node source position can point into the middle
 *     of its block: a branch whose body really starts at bci 6 may report 12
 *     because the first few bytecodes were folded away. Mapping that bci back to
 *     its containing block recovers the real start.
 *   - AbstractBeginNode.getBlockNodes() walks a single basic block; it stops at the
 *     first AbstractBeginNode it meets. A branch body containing a nested if, a
 *     loop or a call with an exception edge is truncated at its first block.
 *
 * A successor's region is the set of blocks it dominates. Dominance is the property
 * the consumer actually needs -- "reaching this bytecode means that edge was taken"
 * -- and it handles loops, self edges and merges without special cases. An earlier
 * version approximated it as "reachable from one successor and not the other",
 * which collapsed to the empty set whenever a successor's block was the condition's
 * own block; that happens for 41% of the conditions in a real image, because the
 * successor's node source position is often still the bci of the If itself.
 *
 * That same drift is why a successor block is recovered from the condition block's
 * successor list by elimination when its position does not identify a distinct
 * block. Nothing is written when the region cannot be determined, so the consumer
 * can fall back rather than be told the successor owns nothing.
 */
public class IfNodeCreationCapturePhase extends BasePhase<HighTierContext> {
    private static Path path = Paths.get("condition_mapping.txt");

    @Override
    public Optional<NotApplicable> notApplicableTo(jdk.graal.compiler.nodes.GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    private static final Object FILE_LOCK = new Object();

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        // One mapping per method, reused across every IfNode in this graph. Building
        // it per IfNode reparses the whole method for every branch it contains.
        Map<ResolvedJavaMethod, MethodCfg> blockIndexCache = new HashMap<>();

        synchronized (FILE_LOCK) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {

                for (IfNode ifnode : graph.getNodes(IfNode.TYPE)) {
                    NodeSourcePosition condPos = ifnode.getNodeSourcePosition();
                    if (condPos == null) {
                        continue;
                    }

                    writer.write("cond: " + condPos.rawToString());
                    writer.newLine();
                    writer.write("true: " + ifnode.trueSuccessor().getNodeSourcePosition().rawToString());
                    writer.newLine();
                    writer.write("false: " + ifnode.falseSuccessor().getNodeSourcePosition().rawToString());
                    writer.newLine();

                    writeRegions(writer, ifnode, condPos, graph, blockIndexCache);

                    int lastTrueBCI = getLastBCI(ifnode.trueSuccessor(), condPos);
                    int lastFalseBCI = getLastBCI(ifnode.falseSuccessor(), condPos);
                    writer.write("end: " + Math.max(lastTrueBCI, lastFalseBCI));
                    writer.newLine();

                    writer.write("--------------------------------------");
                    writer.newLine();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Emits "trueregion:" and "falseregion:" as comma separated bci intervals, for
     * example "6-15,22-30". Nothing is written when the regions cannot be determined,
     * so the consumer can tell "no information" from "an empty region".
     */
    private void writeRegions(BufferedWriter writer, IfNode ifnode, NodeSourcePosition condPos,
                    StructuredGraph graph, Map<ResolvedJavaMethod, MethodCfg> cache) throws IOException {
        ResolvedJavaMethod method = condPos.getMethod();
        MethodCfg cfg = cfgFor(method, graph, cache);
        if (cfg == null) {
            return;
        }

        // A successor's position can belong to an inlined callee rather than to the
        // condition's own method. Climbing the caller chain finds the frame that does,
        // which is the bci the region has to be expressed in.
        BciBlock condBlock = cfg.blockAt(condPos.getBCI());
        BciBlock trueBlock = cfg.blockAt(bciInMethod(ifnode.trueSuccessor().getNodeSourcePosition(), method));
        BciBlock falseBlock = cfg.blockAt(bciInMethod(ifnode.falseSuccessor().getNodeSourcePosition(), method));
        if (condBlock == null) {
            return;
        }

        // The successor position frequently still points at the If's own bci, which
        // resolves to the condition's block rather than to the successor. When one
        // side is identifiable the other is the remaining successor of the condition.
        if (trueBlock == condBlock || trueBlock == null) {
            trueBlock = otherSuccessor(condBlock, falseBlock);
        }
        if (falseBlock == condBlock || falseBlock == null) {
            falseBlock = otherSuccessor(condBlock, trueBlock);
        }
        if (trueBlock == null || falseBlock == null || trueBlock == falseBlock
                        || trueBlock == condBlock || falseBlock == condBlock) {
            return;
        }

        writer.write("trueregion: " + intervals(cfg.dominatedBy(trueBlock)));
        writer.newLine();
        writer.write("falseregion: " + intervals(cfg.dominatedBy(falseBlock)));
        writer.newLine();
    }

    /** The one successor of {@code block} that is not {@code known}, if unambiguous. */
    private static BciBlock otherSuccessor(BciBlock block, BciBlock known) {
        BciBlock found = null;
        for (BciBlock s : block.getSuccessors()) {
            if (s == null || s == known || s == block) {
                continue;
            }
            if (found != null && found != s) {
                return null;
            }
            found = s;
        }
        return found;
    }

    /** The blocks' bci ranges, sorted and with adjacent or overlapping ones merged. */
    private static String intervals(Collection<BciBlock> blocks) {
        List<int[]> ranges = new ArrayList<>();
        for (BciBlock b : blocks) {
            if (b.isInstructionBlock() && b.getStartBci() >= 0 && b.getEndBci() >= b.getStartBci()) {
                ranges.add(new int[]{b.getStartBci(), b.getEndBci()});
            }
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));

        StringBuilder sb = new StringBuilder();
        int[] cur = null;
        for (int[] r : ranges) {
            if (cur != null && r[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], r[1]);
                continue;
            }
            if (cur != null) {
                sb.append(cur[0]).append('-').append(cur[1]).append(',');
            }
            cur = new int[]{r[0], r[1]};
        }
        if (cur != null) {
            sb.append(cur[0]).append('-').append(cur[1]);
        }
        return sb.toString();
    }

    /**
     * A method's bytecode CFG: a bci to block index plus immediate dominators.
     *
     * The index is built from the blocks rather than by calling getInstructionBlock,
     * which asserts its entry is non null and, with assertions disabled, silently
     * returns null.
     */
    private static final class MethodCfg {
        private final BciBlock[] byBci;
        private final BciBlock[] blocks;
        private final int[] idom;      // index into blocks, -1 for the entry and unreached

        MethodCfg(BciBlockMapping mapping, int codeSize) {
            this.blocks = mapping.getBlocks();
            this.byBci = new BciBlock[codeSize];
            for (BciBlock b : blocks) {
                if (!b.isInstructionBlock()) {
                    continue;
                }
                for (int bci = b.getStartBci(); bci <= b.getEndBci() && bci < codeSize; bci++) {
                    if (bci >= 0 && byBci[bci] == null) {
                        byBci[bci] = b;
                    }
                }
            }
            this.idom = computeIdom(mapping.getStartBlock());
        }

        BciBlock blockAt(int bci) {
            return (bci < 0 || bci >= byBci.length) ? null : byBci[bci];
        }

        /** Every block dominated by {@code head}, including itself. */
        List<BciBlock> dominatedBy(BciBlock head) {
            List<BciBlock> out = new ArrayList<>();
            int target = head.getId();
            for (BciBlock b : blocks) {
                for (int at = b.getId(); at >= 0; at = idom[at]) {
                    if (at == target) {
                        out.add(b);
                        break;
                    }
                    if (idom[at] == at) {
                        break;
                    }
                }
            }
            return out;
        }

        /**
         * Immediate dominators, by the usual iterate to a fixed point over a reverse
         * post order. Ids are a dense index into getBlocks(), so they double as slots.
         */
        private int[] computeIdom(BciBlock entry) {
            int n = blocks.length;
            int[] dom = new int[n];
            Arrays.fill(dom, -1);
            if (entry == null) {
                return dom;
            }

            List<BciBlock> rpo = reversePostOrder(entry, n);
            int[] rpoIndex = new int[n];
            Arrays.fill(rpoIndex, Integer.MAX_VALUE);
            for (int i = 0; i < rpo.size(); i++) {
                rpoIndex[rpo.get(i).getId()] = i;
            }

            List<List<BciBlock>> preds = predecessors(n);
            dom[entry.getId()] = entry.getId();

            boolean changed = true;
            while (changed) {
                changed = false;
                for (BciBlock b : rpo) {
                    if (b == entry) {
                        continue;
                    }
                    int newIdom = -1;
                    for (BciBlock p : preds.get(b.getId())) {
                        if (dom[p.getId()] < 0) {
                            continue;   // not yet processed
                        }
                        newIdom = (newIdom < 0) ? p.getId() : intersect(dom, rpoIndex, p.getId(), newIdom);
                    }
                    if (newIdom >= 0 && dom[b.getId()] != newIdom) {
                        dom[b.getId()] = newIdom;
                        changed = true;
                    }
                }
            }
            return dom;
        }

        private List<BciBlock> reversePostOrder(BciBlock entry, int n) {
            List<BciBlock> post = new ArrayList<>(n);
            boolean[] seen = new boolean[n];
            Deque<BciBlock> stack = new ArrayDeque<>();
            Deque<Integer> next = new ArrayDeque<>();
            stack.push(entry);
            next.push(0);
            seen[entry.getId()] = true;
            while (!stack.isEmpty()) {
                BciBlock b = stack.peek();
                int i = next.pop();
                List<BciBlock> sux = b.getSuccessors();
                if (i < sux.size()) {
                    next.push(i + 1);
                    BciBlock s = sux.get(i);
                    if (s != null && !seen[s.getId()]) {
                        seen[s.getId()] = true;
                        stack.push(s);
                        next.push(0);
                    }
                } else {
                    post.add(stack.pop());
                }
            }
            Collections.reverse(post);
            return post;
        }

        private List<List<BciBlock>> predecessors(int n) {
            List<List<BciBlock>> preds = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                preds.add(new ArrayList<>(2));
            }
            for (BciBlock b : blocks) {
                for (BciBlock s : b.getSuccessors()) {
                    if (s != null) {
                        preds.get(s.getId()).add(b);
                    }
                }
            }
            return preds;
        }

        private static int intersect(int[] dom, int[] rpoIndex, int a, int b) {
            while (a != b) {
                while (rpoIndex[a] > rpoIndex[b]) {
                    if (dom[a] < 0 || dom[a] == a) {
                        return b;
                    }
                    a = dom[a];
                }
                while (rpoIndex[b] > rpoIndex[a]) {
                    if (dom[b] < 0 || dom[b] == b) {
                        return a;
                    }
                    b = dom[b];
                }
            }
            return a;
        }
    }

    private static MethodCfg cfgFor(ResolvedJavaMethod method, StructuredGraph graph,
                    Map<ResolvedJavaMethod, MethodCfg> cache) {
        if (method == null) {
            return null;
        }
        if (cache.containsKey(method)) {
            return cache.get(method);
        }
        MethodCfg cfg = null;
        try {
            if (method.getCode() != null) {
                Bytecode code = new ResolvedJavaMethodBytecode(method);
                if (code.getCode() != null && code.getCodeSize() > 0) {
                    BciBlockMapping mapping = BciBlockMapping.create(
                                    new BytecodeStream(code.getCode()), code,
                                    graph.getOptions(), graph.getDebug(), false);
                    cfg = new MethodCfg(mapping, code.getCodeSize());
                }
            }
        } catch (Throwable t) {
            // a method whose bytecode cannot be parsed simply gets no regions
            cfg = null;
        }
        cache.put(method, cfg);
        return cfg;
    }

    /** The bci of the frame belonging to {@code method}, or -1 if there is none. */
    private static int bciInMethod(NodeSourcePosition pos, ResolvedJavaMethod method) {
        while (pos != null) {
            if (Objects.equals(pos.getMethod(), method)) {
                return pos.getBCI();
            }
            pos = pos.getCaller();
        }
        return -1;
    }

    /**
     * Iterates through the block starting at {@code beginNode} and finds the last BCI
     * that belongs to the same method as the original {@code IfNode}.
     */
    private int getLastBCI(AbstractBeginNode beginNode, NodeSourcePosition rootPosition) {
        int lastBCI = -1;
        for (jdk.graal.compiler.nodes.FixedNode node : beginNode.getBlockNodes()) {
            NodeSourcePosition pos = node.getNodeSourcePosition();

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
