package it.unibo.ai.didattica.competition.tablut.client;

import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.Game;
import it.unibo.ai.didattica.competition.tablut.domain.GameAshtonTablut;
import it.unibo.ai.didattica.competition.tablut.domain.State;
import it.unibo.ai.didattica.competition.tablut.domain.State.Pawn;
import it.unibo.ai.didattica.competition.tablut.domain.State.Turn;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class MyAIClient extends TablutClient {

    private Game gameRules;
    private long timeLimit = 58000;
    private Random random = new Random();

    // UCT constant
    private static final double UCT_C = 1.4;
    // rollout randomness (epsilon)
    private static final double ROLLOUT_EPSILON = 0.10;
    // max moves in a single simulation
    private static final int MAX_SIM_MOVES = 150;

    class Node {
        State state;
        Node parent;
        List<Node> children;
        Action actionThatLedToThis;

        List<Action> untriedActions;
        int visits;
        double wins; // cumulative reward (from root player's perspective)

        Node(State state, Node parent, Action action) {
            this.state = state;
            this.parent = parent;
            this.actionThatLedToThis = action;
            this.children = new ArrayList<>();
            this.visits = 0;
            this.wins = 0;
            this.untriedActions = null;
        }

        boolean isTerminal() {
            Turn t = state.getTurn();
            return (t == Turn.WHITEWIN || t == Turn.BLACKWIN || t == Turn.DRAW);
        }

        boolean isFullyExpanded() {
            return untriedActions != null && untriedActions.isEmpty();
        }

        double getUCT(int parentVisits) {
            if (this.visits == 0) {
                return Double.MAX_VALUE; // prioritize unvisited
            }
            double mean = this.wins / this.visits;
            double exploration = UCT_C * Math.sqrt(Math.log(Math.max(1, parentVisits)) / this.visits);
            return mean + exploration;
        }
    }

    public MyAIClient(String player, String name, int timeout, String ip) throws UnknownHostException, IOException {
        super(player, name, timeout, ip);
        this.gameRules = new GameAshtonTablut(0, 0, "logs", "AI_Internal", "AI_Internal");
    }

    public static void main(String[] args) throws UnknownHostException, IOException {
        String role = "WHITE";
        String name = "MyAIPlayerMCTS";
        int timeout = 60;
        String ip = "localhost";

        if (args.length > 0) role = args[0].toUpperCase();
        if (args.length > 1) timeout = Integer.parseInt(args[1]);
        if (args.length > 2) ip = args[2];
        if (args.length > 3) name = args[3];

        MyAIClient client = new MyAIClient(role, name, timeout, ip);
        client.run();
    }

    @Override
    public void run() {
        try {
            this.declareName();
            System.out.println("AI (MCTS-Improved) Connected. My role is: " + this.getPlayer().toString());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        while (true) {
            try {
                this.read();
                State currentState = this.getCurrentState();

                if (currentState == null) {
                    System.err.println("Received null state from server. Waiting...");
                    try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
                    continue;
                }

                Turn currentTurn = currentState.getTurn();

                if (currentTurn == Turn.WHITEWIN || currentTurn == Turn.BLACKWIN || currentTurn == Turn.DRAW) {
                    System.out.println("Game Over. Result: " + currentTurn);
                    break;
                }

                if (currentTurn == this.getPlayer()) {
                    System.out.println("\nIt's my turn (" + this.getPlayer() + "). Thinking with MCTS...");
                    long startTime = System.currentTimeMillis();

                    Action bestAction = findBestMove(currentState);

                    long endTime = System.currentTimeMillis();

                    if (bestAction == null) {
                        System.out.println("No valid move found by the AI. This should not happen.");
                        break;
                    } else {
                        System.out.println("AI chose: " + bestAction + " in " + (endTime - startTime) + "ms");
                        this.write(bestAction);
                    }
                }
            } catch (Exception e) {
                System.out.println("An error occurred: " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }
    }

    private List<Action> orderMovesByHeuristic(State state, List<Action> moves) {
    List<Action> ordered = new ArrayList<>();
    List<Integer> scores = new ArrayList<>();

    for (Action a : moves) {
        int s = fastEvaluateMove(state, a);
        ordered.add(a);
        scores.add(s);
    }

    // simple selection sort (stable with randomness)
    for (int i = 0; i < ordered.size(); i++) {
        int bestIndex = i;
        for (int j = i + 1; j < ordered.size(); j++) {
            if (scores.get(j) > scores.get(bestIndex)) {
                bestIndex = j;
            }
        }
        // swap
        Action tmpA = ordered.get(i);
        ordered.set(i, ordered.get(bestIndex));
        ordered.set(bestIndex, tmpA);

        int tmpS = scores.get(i);
        scores.set(i, scores.get(bestIndex));
        scores.set(bestIndex, tmpS);
    }

    return ordered;
}

    private Action findBestMove(State currentState) throws IOException {
        ((GameAshtonTablut) this.gameRules).clearDrawConditions();

        long startTime = System.currentTimeMillis();
        long endTime = startTime + this.timeLimit;

        Node root = new Node(currentState.clone(), null, null); // clone to avoid side effects
        int simulationCount = 0;

        // initialize root's untriedActions so expansion will have it ready
        try {
            root.untriedActions = new ArrayList<>(generatePossibleActions(root.state));
            // order root moves to prefer better heuristics
            root.untriedActions = orderMovesByHeuristic(root.state, root.untriedActions);

            // root.untriedActions.sort(Comparator.comparingInt(a -> -fastEvaluateMove(root.state, a)));
        } catch (IOException e) {
            // fall back to empty list
            root.untriedActions = new ArrayList<>();
        }

        while (System.currentTimeMillis() < endTime) {
            Node promisingNode = selection(root);

            Node expandedNode = promisingNode;
            if (!promisingNode.isTerminal()) {
                try {
                    expandedNode = expansion(promisingNode);
                } catch (Exception e) {
                    // if expansion fails, skip this iteration
                    continue;
                }
            }

            double playoutResult = 0.5;
            try {
                playoutResult = simulation(expandedNode.state, this.getPlayer());
            } catch (Exception e) {
                playoutResult = 0.5;
            }

            backpropagation(expandedNode, playoutResult);
            simulationCount++;
        }

        System.out.println("MCTS completed " + simulationCount + " simulations in " + (System.currentTimeMillis() - startTime) + "ms.");

        Node bestChild = root.children.stream()
                .filter(n -> n.visits > 0)
                .max((n1, n2) -> Double.compare(n1.wins / (double) n1.visits, n2.wins / (double) n2.visits))
                .orElse(null);

        if (bestChild == null) {
            bestChild = root.children.stream()
                    .max((n1, n2) -> Integer.compare(n1.visits, n2.visits))
                    .orElse(null);
        }

        if (bestChild == null) {
            System.out.println("MCTS found no best child, picking random move.");
            List<Action> emergencyMoves = generatePossibleActions(currentState);
            return emergencyMoves.isEmpty() ? null : emergencyMoves.get(random.nextInt(emergencyMoves.size()));
        }

        return bestChild.actionThatLedToThis;
    }

    private Node selection(Node node) {
        Node current = node;
        while (!current.isTerminal()) {
            if (!current.isFullyExpanded()) {
                return current;
            } else {
                final int parentVisits = Math.max(1, current.visits);
                Node best = current.children.stream()
                        .max((n1, n2) -> Double.compare(n1.getUCT(parentVisits), n2.getUCT(parentVisits)))
                        .orElse(null);
                if (best == null) break;
                current = best;
            }
        }
        return current;
    }

    private Node expansion(Node node) throws IOException {
        if (node.untriedActions == null) {
            node.untriedActions = new ArrayList<>(generatePossibleActions(node.state));
            // order by heuristic descending so good moves are expanded earlier
            node.untriedActions.sort(Comparator.comparingInt(a -> -fastEvaluateMove(node.state, a)));
        }

        if (node.untriedActions.isEmpty()) {
            return node;
        }

        Action action = node.untriedActions.remove(0); // take best available (already sorted)

        State newState = applyAction(node.state.clone(), action);

        Node childNode = new Node(newState, node, action);
        node.children.add(childNode);
        return childNode;
    }

    private double simulation(State state, Turn myPlayer) throws IOException {
        State simState = state.clone();
        int moves = 0;

        while (moves < MAX_SIM_MOVES) {
            Turn winner = simState.getTurn();

            if (winner == Turn.WHITEWIN) return (myPlayer == Turn.WHITE) ? 1.0 : 0.0;
            if (winner == Turn.BLACKWIN) return (myPlayer == Turn.BLACK) ? 1.0 : 0.0;
            if (winner == Turn.DRAW) return 0.5;

            List<Action> possibleMoves = generatePossibleActions(simState);
            if (possibleMoves.isEmpty()) return 0.5;

            Action chosen;
            if (random.nextDouble() < ROLLOUT_EPSILON) {
                // explore
                chosen = possibleMoves.get(random.nextInt(possibleMoves.size()));
            } else {
                // exploit: pick best according to quick heuristic, but to avoid bias,
                // consider top-K and randomly choose among them
                int K = Math.max(1, Math.min(5, possibleMoves.size()));
                State currentSimState = simState;
                List<Action> sorted = possibleMoves.stream()
                        .sorted((a, b) -> Integer.compare(fastEvaluateMove(currentSimState, b), fastEvaluateMove(currentSimState, a)))
                        .collect(Collectors.toList());
                int pickIndex = random.nextInt(K);
                chosen = sorted.get(pickIndex);
            }

            try {
                simState = this.gameRules.checkMove(simState, chosen);
            } catch (Exception e) {
                // if illegal or error, treat as draw or skip this move
                return 0.5;
            }
            moves++;

            // quick terminal check after move
            Turn after = simState.getTurn();
            if (after == Turn.WHITEWIN) return (myPlayer == Turn.WHITE) ? 1.0 : 0.0;
            if (after == Turn.BLACKWIN) return (myPlayer == Turn.BLACK) ? 1.0 : 0.0;
            if (after == Turn.DRAW) return 0.5;
        }

        return 0.5;
    }

    private void backpropagation(Node node, double result) {
        Node temp = node;
        while (temp != null) {
            temp.visits++;

            // Determine which player made the move that led to temp
            // If actionThatLedToThis is null, it's the root; use result as-is
            if (temp.actionThatLedToThis == null) {
                temp.wins += result;
            } else {
                Turn mover = temp.actionThatLedToThis.getTurn();
                if (mover == this.getPlayer()) {
                    // mover is root player: reward as-is
                    temp.wins += result;
                } else {
                    // mover is opponent: invert reward
                    temp.wins += (1.0 - result);
                }
            }

            temp = temp.parent;
        }
    }

    private int fastEvaluateMove(State state, Action action) {
        int score = 0;
        Turn player = action.getTurn();
        Pawn[][] board = state.getBoard();

        // simulate move locally for heuristic (without modifying original board)
        int fromR = action.getRowFrom();
        int fromC = action.getColumnFrom();
        int toR = action.getRowTo();
        int toC = action.getColumnTo();
        Pawn moving = board[fromR][fromC];

        // quick capture estimation
        int captures = fastSimulatedCaptureCheck(board, action);
        if (captures > 0) {
            if (captures >= 10) score += 20000;
            score += 2000 * captures;
        }

        if (player == Turn.WHITE) {
            if (moving == Pawn.KING) {
                int distBefore = kingDistanceToEscape(fromR, fromC);
                int distAfter = kingDistanceToEscape(toR, toC);
                if (distAfter < distBefore) score += 800;
                // prefer increasing nearest black pawn distance
                int beforeNearest = nearestEnemyDistance(board, fromR, fromC, Pawn.BLACK);
                int afterNearest = nearestEnemyDistance(board, toR, toC, Pawn.BLACK);
                if (afterNearest > beforeNearest) score += 200;
            } else {
                // white pawn heuristic: keep pawns nearer to king
                int[] king = findKing(board);
                if (king[0] != -1) {
                    int before = Math.abs(fromR - king[0]) + Math.abs(fromC - king[1]);
                    int after = Math.abs(toR - king[0]) + Math.abs(toC - king[1]);
                    if (after < before) score += 80; // support king
                    else score -= 20;
                }
            }
        } else {
            // BLACK heuristics
            int[] king = findKing(board);
            if (king[0] != -1) {
                int before = Math.abs(fromR - king[0]) + Math.abs(fromC - king[1]);
                int after = Math.abs(toR - king[0]) + Math.abs(toC - king[1]);
                if (after < before) score += 300;
                if (toR == king[0] || toC == king[1]) score += 60; // on same row/col as king
            }
            // penalize leaving large gaps
            score += random.nextInt(8);
        }

        // small random tie-breaker
        score += random.nextInt(20);

        return score;
    }

    private int nearestEnemyDistance(Pawn[][] board, int r, int c, Pawn enemyPawn) {
        int best = 100;
        for (int rr = 0; rr < 9; rr++) {
            for (int cc = 0; cc < 9; cc++) {
                if (board[rr][cc] == enemyPawn) {
                    int d = Math.abs(r - rr) + Math.abs(c - cc);
                    if (d < best) best = d;
                }
            }
        }
        return best;
    }

    private int fastSimulatedCaptureCheck(Pawn[][] board, Action action) {
        int captures = 0;
        int r = action.getRowTo();
        int c = action.getColumnTo();
        Turn turn = action.getTurn();

        Pawn enemy = (turn == Turn.WHITE) ? Pawn.BLACK : Pawn.WHITE;
        Pawn ally = (turn == Turn.WHITE) ? Pawn.WHITE : Pawn.BLACK;
        Pawn king = Pawn.KING;

        // Helper to check bounds
        java.util.function.BiPredicate<Integer, Integer> inBounds = (rr, cc) -> rr >= 0 && rr < 9 && cc >= 0 && cc < 9;

        // UP
        if (inBounds.test(r - 1, c) && inBounds.test(r - 2, c)) {
            Pawn mid = board[r - 1][c];
            Pawn beyond = board[r - 2][c];
            if ((mid == enemy || mid == king) && (beyond == ally || beyond == king || isHostileSquare(r - 2, c))) {
                if (mid == king) captures += 10; else captures++;
            }
        }
        // DOWN
        if (inBounds.test(r + 1, c) && inBounds.test(r + 2, c)) {
            Pawn mid = board[r + 1][c];
            Pawn beyond = board[r + 2][c];
            if ((mid == enemy || mid == king) && (beyond == ally || beyond == king || isHostileSquare(r + 2, c))) {
                if (mid == king) captures += 10; else captures++;
            }
        }
        // LEFT
        if (inBounds.test(r, c - 1) && inBounds.test(r, c - 2)) {
            Pawn mid = board[r][c - 1];
            Pawn beyond = board[r][c - 2];
            if ((mid == enemy || mid == king) && (beyond == ally || beyond == king || isHostileSquare(r, c - 2))) {
                if (mid == king) captures += 10; else captures++;
            }
        }
        // RIGHT
        if (inBounds.test(r, c + 1) && inBounds.test(r, c + 2)) {
            Pawn mid = board[r][c + 1];
            Pawn beyond = board[r][c + 2];
            if ((mid == enemy || mid == king) && (beyond == ally || beyond == king || isHostileSquare(r, c + 2))) {
                if (mid == king) captures += 10; else captures++;
            }
        }

        return captures;
    }

    private int[] findKing(Pawn[][] board) {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c] == Pawn.KING) {
                    return new int[]{r, c};
                }
            }
        }
        return new int[]{-1, -1};
    }

    private int kingDistanceToEscape(int r, int c) {
        int distN = r;
        int distS = 8 - r;
        int distW = c;
        int distE = 8 - c;
        return Math.min(Math.min(distN, distS), Math.min(distW, distE));
    }

    private State applyAction(State state, Action action) {
        try {
            return this.gameRules.checkMove(state, action);
        } catch (Exception e) {
            // print stack for debugging but return unchanged state to avoid crash
            e.printStackTrace();
            return state;
        }
    }

    private List<Action> generatePossibleActions(State state) throws IOException {
        List<Action> validActions = new ArrayList<>();
        Turn player = state.getTurn();
        Pawn[][] board = state.getBoard();

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                Pawn currentPawn = board[r][c];
                boolean isMyPawn = false;
                if (player.equals(Turn.WHITE) && (currentPawn.equals(Pawn.WHITE) || currentPawn.equals(Pawn.KING))) { isMyPawn = true; }
                else if (player.equals(Turn.BLACK) && currentPawn.equals(Pawn.BLACK)) { isMyPawn = true; }

                if (isMyPawn) {
                    String from = state.getBox(r, c);

                    // UP
                    for (int rowTo = r - 1; rowTo >= 0; rowTo--) {
                        if (board[rowTo][c] != Pawn.EMPTY || isCitadel(rowTo, c)) break;
                        if (isMoveLegal(currentPawn, rowTo, c)) validActions.add(new Action(from, state.getBox(rowTo, c), player));
                    }
                    // DOWN
                    for (int rowTo = r + 1; rowTo < 9; rowTo++) {
                        if (board[rowTo][c] != Pawn.EMPTY || isCitadel(rowTo, c)) break;
                        if (isMoveLegal(currentPawn, rowTo, c)) validActions.add(new Action(from, state.getBox(rowTo, c), player));
                    }
                    // LEFT
                    for (int colTo = c - 1; colTo >= 0; colTo--) {
                        if (board[r][colTo] != Pawn.EMPTY || isCitadel(r, colTo)) break;
                        if (isMoveLegal(currentPawn, r, colTo)) validActions.add(new Action(from, state.getBox(r, colTo), player));
                    }
                    // RIGHT
                    for (int colTo = c + 1; colTo < 9; colTo++) {
                        if (board[r][colTo] != Pawn.EMPTY || isCitadel(r, colTo)) break;
                        if (isMoveLegal(currentPawn, r, colTo)) validActions.add(new Action(from, state.getBox(r, colTo), player));
                    }
                }
            }
        }
        return validActions;
    }

    private boolean isMoveLegal(Pawn pawn, int rTo, int cTo) {
        if (rTo == 4 && cTo == 4) return pawn.equals(Pawn.KING);
        if (isCitadel(rTo, cTo)) return false;
        return true;
    }

    private boolean isHostileSquare(int r, int c) {
        if (r == 4 && c == 4) return true;
        return isCitadel(r, c);
    }

    private boolean isCitadel(int r, int c) {
        if (r == 0 && (c == 3 || c == 4 || c == 5)) return true;
        if (r == 1 && c == 4) return true;
        if (r == 3 && (c == 0 || c == 8)) return true;
        if (r == 4 && (c == 0 || c == 1 || c == 7 || c == 8)) return true;
        if (r == 5 && (c == 0 || c == 8)) return true;
        if (r == 7 && c == 4) return true;
        if (r == 8 && (c == 3 || c == 4 || c == 5)) return true;
        return false;
    }
}
