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
import java.util.List;
import java.util.Random;

public class MyAIClient extends TablutClient {

    private Game gameRules;
    private long timeLimit = 58000; 
    private Random random = new Random();

    class Node {
        State state;
        Node parent;
        List<Node> children;
        Action actionThatLedToThis; 

        List<Action> untriedActions;
        int visits;
        double wins; // win rate

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
                return Double.MAX_VALUE; // Prioritize unvisited nodes
            }
            double exploitation = this.wins / this.visits;
            // C-constant (sqrt(2)) balances exploration/exploitation
            double exploration = Math.sqrt(2) * Math.sqrt(Math.log(parentVisits) / this.visits);
            return exploitation + exploration;
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
        
        //  command-line arguments
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
            System.out.println("AI (MCTS-FastSim) Connected. My role is: " + this.getPlayer().toString());
        } catch (Exception e) { e.printStackTrace(); return; }

        while (true) {
            try {
                // read the state from the server
                this.read(); 
                State currentState = this.getCurrentState();
                
                if (currentState == null) {
                    System.err.println("Received null state from server. Waiting...");
                    try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
                    continue;
                }

                Turn currentTurn = currentState.getTurn();
                
                // Check for game over
                if (currentTurn == Turn.WHITEWIN || currentTurn == Turn.BLACKWIN || currentTurn == Turn.DRAW) {
                    System.out.println("Game Over. Result: " + currentTurn);
                    break;
                }
                
                // If it's our turn, think and play
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

    private Action findBestMove(State currentState) throws IOException {
        ((GameAshtonTablut) this.gameRules).clearDrawConditions();
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + this.timeLimit;
        
        Node root = new Node(currentState, null, null);
        int simulationCount = 0;

        while (System.currentTimeMillis() < endTime) {
            // find a promising node
            Node promisingNode = selection(root);
            
            // create one new child (if not terminal)
            Node expandedNode = promisingNode;
            if (!promisingNode.isTerminal()) {
                expandedNode = expansion(promisingNode);
            }
            
            double playoutResult = simulation(expandedNode.state, this.getPlayer());
            
            //  update wins/visits up the tree
            backpropagation(expandedNode, playoutResult);
            
            simulationCount++;
        }

        System.out.println("MCTS completed " + simulationCount + " simulations in " + (System.currentTimeMillis() - startTime) + "ms.");

        Node bestChild = root.children.stream()
            .filter(n -> n.visits > 0) // Avoid division by zero
            .max((n1, n2) -> Double.compare(n1.wins / n1.visits, n2.wins / n2.visits))
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
        while (!node.isTerminal()) {
            if (!node.isFullyExpanded()) {
                return node; 
            } else {
                final int parentVisits = node.visits;
                node = node.children.stream()
                    .max((n1, n2) -> Double.compare(n1.getUCT(parentVisits), n2.getUCT(parentVisits)))
                    .get(); // Get child with highest UCT score
            }
        }
        return node;
    }

 
    
    private Node expansion(Node node) throws IOException {
        if (node.untriedActions == null) {
            node.untriedActions = new ArrayList<>(generatePossibleActions(node.state));
        }

        if (node.untriedActions.isEmpty()) {
            return node; 
        }

        // Pop one random untried action
        Action action = node.untriedActions.remove(random.nextInt(node.untriedActions.size()));
        
        // Apply the action 
        State newState = applyAction(node.state.clone(), action);
        
        // Create the new child node
        Node childNode = new Node(newState, node, action);
        node.children.add(childNode);
        return childNode;
    }

    
    private double simulation(State state, Turn myPlayer) throws IOException {
        State simState = state.clone();
        int maxSimMoves = 100;
        int moves = 0;

        while (moves < maxSimMoves) {
            Turn winner = simState.getTurn();
            
            // Check for terminal state
            if (winner == Turn.WHITEWIN) return (myPlayer == Turn.WHITE) ? 1.0 : 0.0;
            if (winner == Turn.BLACKWIN) return (myPlayer == Turn.BLACK) ? 1.0 : 0.0;
            if (winner == Turn.DRAW) return 0.5;

            // Get all legal moves 
            List<Action> possibleMoves = generatePossibleActions(simState);
            if (possibleMoves.isEmpty()) return 0.5; // No moves = draw

            //  Pick the best "fast" move
            Action bestSimMove = null;
            int bestSimScore = Integer.MIN_VALUE;

            for (Action action : possibleMoves) {
                int score = fastEvaluateMove(simState, action);
                if (score > bestSimScore) {
                    bestSimScore = score;
                    bestSimMove = action;
                }
            }
            
            try {
                simState = this.gameRules.checkMove(simState, bestSimMove); 
            } catch (Exception e) {
                return 0.5; 
            }
            moves++;
        }
        
        return 0.5; // Max moves reached, call it a draw
    }

    
    private void backpropagation(Node node, double result) {
        Node temp = node;
        while (temp != null) {
            temp.visits++;
            temp.wins += result;
            temp = temp.parent; // Move up the tree
        }
    }


   
    private int fastEvaluateMove(State state, Action action) {
        int score = 0;
        Turn player = action.getTurn();
        Pawn[][] board = state.getBoard();

        //  Check for captures
        int captures = fastSimulatedCaptureCheck(board, action);
        if (captures > 0) {
            if (captures >= 10) score += 50000;
            score += 1000 * captures; // High priority!
        }

        if (player == Turn.WHITE) {
            Pawn pawn = board[action.getRowFrom()][action.getColumnFrom()];
            if (pawn == Pawn.KING) {
                // Reward moving king closer to an escape
                int distBefore = kingDistanceToEscape(action.getRowFrom(), action.getColumnFrom());
                int distAfter = kingDistanceToEscape(action.getRowTo(), action.getColumnTo());
                if (distAfter < distBefore) {
                    score += 500;
                }

                // Reward moving *away* from black pawns
                int nearestBlackPawnDistBefore = 100;
                int nearestBlackPawnDistAfter = 100;
                for (int r = 0; r < 9; r++) {
                    for (int c = 0; c < 9; c++) {
                        if (board[r][c] == Pawn.BLACK) {
                            int distB = Math.abs(action.getRowFrom() - r) + Math.abs(action.getColumnFrom() - c);
                            if (distB < nearestBlackPawnDistBefore) nearestBlackPawnDistBefore = distB;
                            
                            int distA = Math.abs(action.getRowTo() - r) + Math.abs(action.getColumnTo() - c);
                            if (distA < nearestBlackPawnDistAfter) nearestBlackPawnDistAfter = distA;
                        }
                    }
                }
                if (nearestBlackPawnDistAfter > nearestBlackPawnDistBefore) {
                    score += 100; // Reward moving *away* from danger
                }
            }
        } else { 
            // player is BLACK
            // Find the king 
            int[] kingPos = findKing(board);
            
            if (kingPos[0] != -1) {
                // Reward moving a pawn closer to the king
                int distBefore = Math.abs(action.getRowFrom() - kingPos[0]) + Math.abs(action.getColumnFrom() - kingPos[1]);
                int distAfter = Math.abs(action.getRowTo() - kingPos[0]) + Math.abs(action.getColumnTo() - kingPos[1]);
                if (distAfter < distBefore) {
                    score += 200;
                }
                
                // Reward blocking an escape route
                int r = action.getRowTo();
                int c = action.getColumnTo();
                if (r == kingPos[0] || c == kingPos[1]) { 
                    // Moving onto the king's row or column
                    score += 50; 
                }
            }
        }

        // randomness to break ties
        score += random.nextInt(10);
        
        return score;
    }

    
    private int fastSimulatedCaptureCheck(Pawn[][] board, Action action) {
        int captures = 0;
        int r = action.getRowTo();
        int c = action.getColumnTo();
        Turn turn = action.getTurn();
        
        Pawn enemy = (turn == Turn.WHITE) ? Pawn.BLACK : Pawn.WHITE;
        Pawn ally = (turn == Turn.WHITE) ? Pawn.WHITE : Pawn.BLACK;
        Pawn king = Pawn.KING;

        // Check for captures UP (r-1)
        if (r > 1 && (board[r-1][c] == enemy || board[r-1][c] == king) && (board[r-2][c] == ally || board[r-2][c] == king || isHostileSquare(r-2, c))) {
            if (board[r-1][c] == king) captures += 10; // Capturing king
            else captures++;
        }
        // Check for captures DOWN (r+1)
        if (r < 7 && (board[r+1][c] == enemy || board[r+1][c] == king) && (board[r+2][c] == ally || board[r+2][c] == king || isHostileSquare(r+2, c))) {
            if (board[r+1][c] == king) captures += 10;
            else captures++;
        }
        // Check for captures LEFT (c-1)
        if (c > 1 && (board[r][c-1] == enemy || board[r][c-1] == king) && (board[r][c-2] == ally || board[r][c-2] == king || isHostileSquare(r, c-2))) {
            if (board[r][c-1] == king) captures += 10;
            else captures++;
        }
        // Check for captures RIGHT (c+1)
        if (c < 7 && (board[r][c+1] == enemy || board[r][c+1] == king) && (board[r][c+2] == ally || board[r][c+2] == king || isHostileSquare(r, c+2))) {
            if (board[r][c+1] == king) captures += 10;
            else captures++;
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
        return new int[]{-1, -1}; // Not found
    }

    //find the King's Manhattan distance to the closest edge
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
                    
                    // Check moves UP
                    for (int rowTo = r - 1; rowTo >= 0; rowTo--) {
                        if (board[rowTo][c] != Pawn.EMPTY || isCitadel(rowTo, c)) break; // Path blocked
                        if (isMoveLegal(currentPawn, rowTo, c)) {
                            validActions.add(new Action(from, state.getBox(rowTo, c), player));
                        }
                    }
                    // Check moves DOWN
                    for (int rowTo = r + 1; rowTo < 9; rowTo++) {
                        if (board[rowTo][c] != Pawn.EMPTY || isCitadel(rowTo, c)) break;
                        if (isMoveLegal(currentPawn, rowTo, c)) {
                            validActions.add(new Action(from, state.getBox(rowTo, c), player));
                        }
                    }
                    // Check moves LEFT
                    for (int colTo = c - 1; colTo >= 0; colTo--) {
                        if (board[r][colTo] != Pawn.EMPTY || isCitadel(r, colTo)) break;
                        if (isMoveLegal(currentPawn, r, colTo)) {
                            validActions.add(new Action(from, state.getBox(r, colTo), player));
                        }
                    }
                    // Check moves RIGHT
                    for (int colTo = c + 1; colTo < 9; colTo++) {
                        if (board[r][colTo] != Pawn.EMPTY || isCitadel(r, colTo)) break;
                        if (isMoveLegal(currentPawn, r, colTo)) {
                            validActions.add(new Action(from, state.getBox(r, colTo), player));
                        }
                    }
                }
            }
        }
        return validActions;
    }

    // Checks if a pawn can legally LAND on a square
    private boolean isMoveLegal(Pawn pawn, int rTo, int cTo) {
        if (rTo == 4 && cTo == 4) return pawn.equals(Pawn.KING); // Only king can land on Throne
        if (isCitadel(rTo, cTo)) return false; // No pawn can land on a citadel
        return true;
    }
    
    // Checks if a square is a "hostile" square (Throne or Citadel) for captures
    private boolean isHostileSquare(int r, int c) {
        if (r == 4 && c == 4) return true; // Throne
        return isCitadel(r, c);
    }

    // Checks if a square is a citadel (not the throne)
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